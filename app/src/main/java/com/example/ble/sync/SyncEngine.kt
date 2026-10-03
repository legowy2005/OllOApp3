package com.example.ble.sync

import com.example.ble.BleConstants
import com.example.ble.GlassesConnectionState
import com.example.ble.GlassesTransport
import com.example.ble.IncomingPacket
import com.example.ble.PacketBuilder
import com.example.ble.PacketTypes
import com.example.core.AsciiTransliteration
import com.example.core.DeviceLimits
import com.example.data.entity.CardEntity
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
        // Step 1: Connect and prepare
        if (transport.connectionState.value != GlassesConnectionState.CONNECTED) {
            _syncState.value = SyncState.Connecting
            onProgress?.invoke(0.05f, "Connecting to glasses...")
            val connected = transport.connect()
            if (!connected) {
                _syncState.value = SyncState.Failed("Could not connect to glasses", "Connection")
                return
            }
        }

        // Step 2: (Optional) Send GET_INFO; wait up to 1.5s for INFO
        _syncState.value = SyncState.CheckingInfo
        onProgress?.invoke(0.1f, "Querying glasses firmware capabilities...")
        val infoPacket = queryInfoWithTimeout(transport, BleConstants.GET_INFO_TIMEOUT_MS)
        if (infoPacket != null) {
            DeviceLimits.updateLimits(
                maxW = infoPacket.maxWidth,
                maxH = infoPacket.maxHeight,
                maxImgBytes = infoPacket.maxImageBytes,
                maxTxtBytes = infoPacket.maxTextBytes
            )
        }

        // Step 3: Send GET_STORAGE; wait for STORAGE_INFO
        _syncState.value = SyncState.CheckingStorage
        onProgress?.invoke(0.15f, "Checking glasses storage...")
        val storageInfo = queryStorageWithRetries(transport)
        if (storageInfo == null) {
            _syncState.value = SyncState.Failed("Glasses did not respond to storage query", "Storage Query")
            return
        }
        _totalStorageBytes.value = storageInfo.totalBytes
        _freeStorageBytes.value = storageInfo.freeBytes

        // Step 4: Check if synced deck fits device
        val neededBytes = repository.calculateOfflineEstimate()
        if (neededBytes > storageInfo.freeBytes) {
            val neededKb = (neededBytes + 1023) / 1024
            val freeKb = storageInfo.freeBytes / 1024
            val msg = "Deck is too large for the glasses' remaining storage (needs about $neededKb KB, free $freeKb KB)"
            _syncState.value = SyncState.Failed(msg, "Storage Check", canRetry = false)
            return
        }

        // Step 5: Collect cards from folders with include_in_sync = true
        val cardsToSync = repository.getCardsToSync()
        val cardCount = cardsToSync.size

        // Collect unique image ids
        val imageIds = mutableSetOf<Long>()
        for (c in cardsToSync) {
            c.frontImageId?.let { if (it > 0) imageIds.add(it) }
            c.backImageId?.let { if (it > 0) imageIds.add(it) }
        }
        val imagesToSync = repository.getImagesForSync(imageIds.toList())
        val imageMap = imagesToSync.associateBy { it.id }

        // Step 6: Send BEGIN_SYNC with cardCount = N
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

        // Step 7: Send cards in order, index 0..N-1
        for (index in 0 until cardCount) {
            val card = cardsToSync[index]
            val frontClean = AsciiTransliteration.sanitizeForGlasses(card.frontText).cleanText
            val backClean = AsciiTransliteration.sanitizeForGlasses(card.backText).cleanText
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
                backText = backClean
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

        // Step 8: Send images
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
                _syncState.value = SyncState.Failed("Image transfer timed out at image $imagesSent", "Image Transfer")
                return
            }
        }

        // Step 9: Send END_SYNC
        _syncState.value = SyncState.Finalizing
        onProgress?.invoke(0.95f, "Finalizing sync on glasses...")

        coroutineScope {
            // Listen for automatic STORAGE_INFO in parallel before ending sync
            val storageDeferred = async {
                withTimeoutOrNull(3000L) {
                    transport.notifications
                        .filter { it is IncomingPacket.StorageInfo }
                        .first() as IncomingPacket.StorageInfo
                }
            }

            val endSyncSuccess = executeWithRetries(
                stepName = "END_SYNC",
                transport = transport,
                packetToSend = PacketBuilder.buildEndSync(),
                expectedRefType = PacketTypes.END_SYNC
            )
            if (!endSyncSuccess) {
                storageDeferred.cancel()
                _syncState.value = SyncState.Failed("Failed to finalize sync", "END_SYNC")
                return@coroutineScope
            }

            // Step 10: Automatic STORAGE_INFO received
            val finalStorage = storageDeferred.await()
            if (finalStorage != null) {
                _freeStorageBytes.value = finalStorage.freeBytes
                _totalStorageBytes.value = finalStorage.totalBytes
            }

            onProgress?.invoke(1.0f, "Sync complete!")
            _syncState.value = SyncState.Success(
                cardsSynced = cardCount,
                imagesSynced = totalImages,
                freeBytesOnGlasses = _freeStorageBytes.value ?: storageInfo.freeBytes
            )
        }
    }

    private suspend fun queryInfoWithTimeout(transport: GlassesTransport, timeoutMs: Long): IncomingPacket.Info? {
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val deferred = async {
                    transport.notifications
                        .filter { it is IncomingPacket.Info }
                        .first() as IncomingPacket.Info
                }
                transport.sendPacket(PacketBuilder.buildGetInfo())
                deferred.await()
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

            if (status != null && status.isOk) {
                return true
            }
            delay(100)
        }
        return false
    }

    private suspend fun transferImageWithRetries(
        transport: GlassesTransport,
        image: ImageEntity,
        onChunkProgress: (Long, Long) -> Unit
    ): Boolean {
        for (attempt in 1..3) {
            val beginPacket = PacketBuilder.buildImgBegin(
                id = image.id,
                width = image.width,
                height = image.height,
                dataLen = image.deviceData.size.toLong()
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

            // Deduplication: If glasses already have image, skip chunks!
            if (beginStatus.isAlreadyHaveImage) {
                return true
            }

            if (!beginStatus.isOk) {
                delay(100)
                continue
            }

            // Wait 100 ms to let glasses prepare flash buffer
            delay(BleConstants.IMG_BEGIN_SETTLE_DELAY_MS)

            // Slice into chunks of max payload (maxWriteBytes - 5)
            val maxChunkBytes = transport.getMaxPayloadSize() - 5
            val data = image.deviceData
            var offset = 0

            while (offset < data.size) {
                val chunkSize = minOf(maxChunkBytes, data.size - offset)
                val chunkPacket = PacketBuilder.buildImgChunk(offset.toLong(), data, offset, chunkSize)
                transport.sendPacket(chunkPacket)
                offset += chunkSize
                onChunkProgress(offset.toLong(), data.size.toLong())

                // 12 ms chunk pacing (Section 5.7)
                delay(BleConstants.CHUNK_PACING_DELAY_MS)
            }

            // Checksum = (sum of all pixel data bytes) mod 256
            var sum = 0
            for (b in data) {
                sum += (b.toInt() and 0xFF)
            }
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

            if (endStatus != null && endStatus.isOk) {
                return true
            }

            delay(100)
        }
        return false
    }
}
