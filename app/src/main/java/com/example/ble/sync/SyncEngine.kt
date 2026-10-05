package com.example.ble.sync

import com.example.ble.BleConstants
import com.example.ble.GlassesConnectionState
import com.example.ble.GlassesTransport
import com.example.ble.IncomingPacket
import com.example.ble.PacketBuilder
import com.example.ble.PacketTypes
import com.example.core.AsciiTransliteration
import com.example.core.DeviceLimits
import com.example.data.entity.ImageEntity
import com.example.data.repository.OlloRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed class SyncState {
    data object Idle : SyncState()
    data object Connecting : SyncState()
    data object CheckingInfo : SyncState()
    data object CheckingStorage : SyncState()
    data class SendingCards(val current: Int, val total: Int) : SyncState()
    data class SendingImages(val current: Int, val total: Int, val currentChunkOffset: Long, val totalBytes: Long) : SyncState()
    data object Finalizing : SyncState()
    data class Success(val cardsSynced: Int, val imagesSynced: Int, val freeBytesOnGlasses: Long) : SyncState()
    data class Failed(val errorMessage: String, val stepName: String, val canRetry: Boolean = true) : SyncState()
}

class SyncEngine(
    private val repository: OlloRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _freeStorageBytes = MutableStateFlow<Long?>(null)
    val freeStorageBytes: StateFlow<Long?> = _freeStorageBytes.asStateFlow()

    private val _totalStorageBytes = MutableStateFlow<Long>(BleConstants.DEFAULT_FLASH_TOTAL_BYTES)
    val totalStorageBytes: StateFlow<Long> = _totalStorageBytes.asStateFlow()

    private var syncJob: Job? = null

    private companion object {
        const val END_SYNC_TIMEOUT_MS = 12_000L
    }

    fun cancelSync() {
        syncJob?.cancel()
        _syncState.value = SyncState.Idle
    }

    fun startSync(
        transport: GlassesTransport,
        onProgress: ((Float, String) -> Unit)? = null
    ) {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            runSyncSequence(transport, onProgress)
        }
    }

    private suspend fun runSyncSequence(
        transport: GlassesTransport,
        onProgress: ((Float, String) -> Unit)?
    ) {
        if (transport.connectionState.value != GlassesConnectionState.CONNECTED) {
            _syncState.value = SyncState.Connecting
            onProgress?.invoke(0.05f, "Connecting to glasses...")
            if (!transport.connect()) {
                _syncState.value = SyncState.Failed("Could not connect to glasses", "Connection")
                return
            }
        }

        _syncState.value = SyncState.CheckingInfo
        onProgress?.invoke(0.1f, "Querying glasses firmware capabilities...")
        val infoPacket = queryInfoWithTimeout(transport, BleConstants.GET_INFO_TIMEOUT_MS)
        if (infoPacket != null) {
            DeviceLimits.updateLimits(
                maxW = infoPacket.maxWidth,
                maxH = infoPacket.maxHeight,
                maxImgBytes = infoPacket.maxImageBytes,
                maxTxtBytes = infoPacket.maxTextBytes,
                colorSupported = infoPacket.supportsColor
            )
        }

        _syncState.value = SyncState.CheckingStorage
        onProgress?.invoke(0.15f, "Checking glasses storage...")
        val storageInfo = queryStorageWithRetries(transport)
        if (storageInfo == null) {
            _syncState.value = SyncState.Failed("Glasses did not respond to storage query", "Storage Query")
            return
        }
        _totalStorageBytes.value = storageInfo.totalBytes
        _freeStorageBytes.value = storageInfo.freeBytes

        val neededBytes = repository.calculateOfflineEstimate()
        if (neededBytes > storageInfo.freeBytes) {
            val neededKb = (neededBytes + 1023) / 1024
            val freeKb = storageInfo.freeBytes / 1024
            val msg = "Deck is too large for the glasses' remaining storage (needs about $neededKb KB, free $freeKb KB)"
            _syncState.value = SyncState.Failed(msg, "Storage Check", canRetry = false)
            return
        }

        // Resolve folder names once and keep the same order as the card transfer.
        val cardsWithFolders = repository.getCardsToSyncWithFolders()
        val cardCount = cardsWithFolders.size

        val imageIds = mutableSetOf<Long>()
        for (item in cardsWithFolders) {
            val card = item.card
            card.frontImageId?.let { if (it > 0) imageIds.add(it) }
            card.backImageId?.let { if (it > 0) imageIds.add(it) }
        }
        val imagesToSync = repository.getImagesForSync(imageIds.toList())
        val imageMap = imagesToSync.associateBy { it.id }

        val beginSyncSuccess = executeWithRetries(
            stepName = "BEGIN_SYNC",
            transport = transport,
            packetToSend = PacketBuilder.buildBeginSync(cardCount),
            expectedRefType = PacketTypes.BEGIN_SYNC
        )
        if (!beginSyncSuccess) {
            _syncState.value = SyncState.Failed("Failed to initialize sync session", "BEGIN_SYNC")
            return
        }

        for (index in 0 until cardCount) {
            val item = cardsWithFolders[index]
            val card = item.card
            val frontClean = AsciiTransliteration.sanitizeForGlasses(card.frontText).cleanText
            val backClean = AsciiTransliteration.sanitizeForGlasses(card.backText).cleanText
            val folderClean = AsciiTransliteration
                .sanitizeForGlasses(item.folderName)
                .cleanText
                .take(BleConstants.MAX_FOLDER_NAME_BYTES)
                .ifBlank { "OllO" }

            val frontImgId = card.frontImageId ?: 0L
            val backImgId = card.backImageId ?: 0L

            val cardProgress = 0.2f + (0.4f * (index.toFloat() / cardCount.coerceAtLeast(1)))
            _syncState.value = SyncState.SendingCards(index + 1, cardCount)
            onProgress?.invoke(cardProgress, "Sending cards (${index + 1}/$cardCount)...")

            val cardPacket = PacketBuilder.buildCard(
                index = index,
                frontImgId = frontImgId,
                backImgId = backImgId,
                frontText = frontClean,
                backText = backClean,
                folderName = folderClean
            )

            val cardSuccess = executeWithRetries(
                stepName = "CARD $index",
                transport = transport,
                packetToSend = cardPacket,
                expectedRefType = PacketTypes.CARD
            )
            if (!cardSuccess) {
                _syncState.value = SyncState.Failed("Connection lost at card ${index + 1}", "Card Transfer")
                return
            }
        }

        var imagesSent = 0
        val totalImages = imageIds.size
        for (imgId in imageIds) {
            imagesSent++
            val image = imageMap[imgId]
            if (image == null) continue

            val imgProgress = 0.6f + (0.3f * (imagesSent.toFloat() / totalImages.coerceAtLeast(1)))
            _syncState.value = SyncState.SendingImages(imagesSent, totalImages, 0, image.deviceData.size.toLong())
            onProgress?.invoke(imgProgress, "Sending images ($imagesSent/$totalImages)...")

            val transferSuccess = transferImageWithRetries(transport, image) { offset, total ->
                _syncState.value = SyncState.SendingImages(imagesSent, totalImages, offset, total)
            }
            if (!transferSuccess) {
                val message = if (image.format != 0 && !DeviceLimits.supportsColor) {
                    "Color image $imagesSent cannot be sent: this glasses firmware does not support RGB565"
                } else {
                    "Image transfer failed at image $imagesSent"
                }
                _syncState.value = SyncState.Failed(message, "Image Transfer", canRetry = false)
                return
            }
        }

        _syncState.value = SyncState.Finalizing
        onProgress?.invoke(0.95f, "Finalizing sync on glasses...")

        val finalizeResult = finalizeSync(transport)
        if (finalizeResult == null) {
            _syncState.value = SyncState.Failed("Failed to finalize sync", "END_SYNC")
            return
        }

        finalizeResult.storage?.let {
            _freeStorageBytes.value = it.freeBytes
            _totalStorageBytes.value = it.totalBytes
        }

        onProgress?.invoke(1.0f, "Sync complete!")
        _syncState.value = SyncState.Success(
            cardsSynced = cardCount,
            imagesSynced = totalImages,
            freeBytesOnGlasses = _freeStorageBytes.value ?: storageInfo.freeBytes
        )
    }

    private data class FinalizeResult(val storage: IncomingPacket.StorageInfo?)

    private suspend fun finalizeSync(transport: GlassesTransport): FinalizeResult? {
        for (attempt in 1..2) {
            val reply = withTimeoutOrNull(END_SYNC_TIMEOUT_MS) {
                coroutineScope {
                    val deferred = async {
                        transport.notifications
                            .filter {
                                (it is IncomingPacket.Status && it.refType == PacketTypes.END_SYNC) ||
                                    it is IncomingPacket.StorageInfo
                            }
                            .first()
                    }
                    val sent = transport.sendPacket(PacketBuilder.buildEndSync())
                    if (!sent) null else deferred.await()
                }
            }

            when (reply) {
                is IncomingPacket.StorageInfo -> return FinalizeResult(reply)
                is IncomingPacket.Status -> {
                    if (!reply.isOk) return null
                    val storage = withTimeoutOrNull(1500L) {
                        transport.notifications
                            .filter { it is IncomingPacket.StorageInfo }
                            .first() as IncomingPacket.StorageInfo
                    }
                    return FinalizeResult(storage)
                }
                else -> delay(300)
            }
        }
        return null
    }

    private suspend fun queryInfoWithTimeout(
        transport: GlassesTransport,
        timeoutMs: Long
    ): IncomingPacket.Info? {
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val deferred = async {
                    transport.notifications
                        .filter { it is IncomingPacket.Info }
                        .first() as IncomingPacket.Info
                }
                val sent = transport.sendPacket(PacketBuilder.buildGetInfo())
                if (!sent) null else deferred.await()
            }
        }
    }

    private suspend fun queryStorageWithRetries(transport: GlassesTransport): IncomingPacket.StorageInfo? {
        for (attempt in 1..3) {
            val reply = withTimeoutOrNull(BleConstants.ACK_TIMEOUT_MS) {
                coroutineScope {
                    val deferred = async {
                        transport.notifications
                            .filter { it is IncomingPacket.StorageInfo }
                            .first() as IncomingPacket.StorageInfo
                    }
                    val sent = transport.sendPacket(PacketBuilder.buildGetStorage())
                    if (!sent) null else deferred.await()
                }
            }
            if (reply != null) return reply
            delay(100)
        }
        return null
    }

    private suspend fun executeWithRetries(
        stepName: String,
        transport: GlassesTransport,
        packetToSend: ByteArray,
        expectedRefType: Byte,
        maxRetries: Int = 3
    ): Boolean {
        for (attempt in 1..maxRetries) {
            val status = withTimeoutOrNull(BleConstants.ACK_TIMEOUT_MS) {
                coroutineScope {
                    val deferred = async {
                        transport.notifications
                            .filter { it is IncomingPacket.Status && it.refType == expectedRefType }
                            .first() as IncomingPacket.Status
                    }
                    val sent = transport.sendPacket(packetToSend)
                    if (!sent) null else deferred.await()
                }
            }

            if (status != null && status.isOk)
                return true

            delay(100)
        }
        return false
    }

    private suspend fun transferImageWithRetries(
        transport: GlassesTransport,
        image: ImageEntity,
        onChunkProgress: (Long, Long) -> Unit
    ): Boolean {
        if (image.format != 0 && !DeviceLimits.supportsColor)
            return false

        for (attempt in 1..3) {
            val beginPacket = PacketBuilder.buildImgBegin(
                id = image.id,
                width = image.width,
                height = image.height,
                dataLen = image.deviceData.size.toLong(),
                format = image.format
            )

            val beginStatus = withTimeoutOrNull(BleConstants.ACK_TIMEOUT_MS) {
                coroutineScope {
                    val deferred = async {
                        transport.notifications
                            .filter { it is IncomingPacket.Status && it.refType == PacketTypes.IMG_BEGIN }
                            .first() as IncomingPacket.Status
                    }
                    val sent = transport.sendPacket(beginPacket)
                    if (!sent) null else deferred.await()
                }
            } ?: continue

            if (beginStatus.isAlreadyHaveImage)
                return true

            if (!beginStatus.isOk) {
                delay(100)
                continue
            }

            delay(BleConstants.IMG_BEGIN_SETTLE_DELAY_MS)

            val maxChunkBytes = (transport.getMaxPayloadSize() - 5).coerceAtLeast(1)
            val data = image.deviceData
            var offset = 0

            while (offset < data.size) {
                val chunkSize = minOf(maxChunkBytes, data.size - offset)
                val chunkPacket = PacketBuilder.buildImgChunk(offset.toLong(), data, offset, chunkSize)
                if (!transport.sendPacket(chunkPacket)) {
                    return false
                }

                offset += chunkSize
                onChunkProgress(offset.toLong(), data.size.toLong())
                delay(BleConstants.CHUNK_PACING_DELAY_MS)
            }

            var sum = 0
            for (b in data)
                sum += (b.toInt() and 0xFF)
            val checksum = sum and 0xFF

            val endStatus = withTimeoutOrNull(BleConstants.ACK_TIMEOUT_MS) {
                coroutineScope {
                    val deferred = async {
                        transport.notifications
                            .filter { it is IncomingPacket.Status && it.refType == PacketTypes.IMG_END }
                            .first() as IncomingPacket.Status
                    }
                    val sent = transport.sendPacket(PacketBuilder.buildImgEnd(checksum))
                    if (!sent) null else deferred.await()
                }
            }

            if (endStatus != null && endStatus.isOk)
                return true

            delay(100)
        }
        return false
    }
}
