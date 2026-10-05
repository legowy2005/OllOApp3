package com.example.ble

import com.example.core.DeviceLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SimulatedGlasses(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val flashTotalBytes: Long = BleConstants.DEFAULT_FLASH_TOTAL_BYTES,
    var onPacketLogged: ((direction: String, packetName: String, hexData: String, notes: String) -> Unit)? = null
) : GlassesTransport {

    private val _connectionState = MutableStateFlow(GlassesConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<GlassesConnectionState> = _connectionState.asStateFlow()

    private val _notifications = MutableSharedFlow<IncomingPacket>(replay = 1, extraBufferCapacity = 64)
    override val notifications: SharedFlow<IncomingPacket> = _notifications.asSharedFlow()

    private val _deviceName = MutableStateFlow<String?>("Ollo glasses (Simulator)")
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(-55)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val activeDeck = mutableListOf<SimulatedCard>()
    private val storedImages = mutableMapOf<Long, ByteArray>()
    private var usedFlashBytes: Long = 12_288L

    private var isSyncInProgress = false
    private var expectedCardCount = 0
    private val stagingDeck = mutableListOf<SimulatedCard>()

    private var currentImageId: Long? = null
    private var currentImageWidth: Int = 0
    private var currentImageHeight: Int = 0
    private var currentImageFormat: Int = 0
    private var currentImageDataLen: Long = 0L
    private val currentImageReceivedBytes = mutableListOf<Byte>()

    var dropNextAck: Boolean = false
    var corruptNextChunk: Boolean = false
    var autoDisconnectJob: Job? = null

    data class SimulatedCard(
        val index: Int,
        val frontImgId: Long,
        val backImgId: Long,
        val folderName: String,
        val frontText: String,
        val backText: String
    )

    override suspend fun connect(addressOrId: String?): Boolean {
        _connectionState.value = GlassesConnectionState.CONNECTING
        delay(300)
        _connectionState.value = GlassesConnectionState.CONNECTED
        onPacketLogged?.invoke("SIM", "CONNECTED", "", "Connected to simulated ESP32-S3")
        return true
    }

    override suspend fun disconnect() {
        _connectionState.value = GlassesConnectionState.DISCONNECTING
        delay(100)
        isSyncInProgress = false
        stagingDeck.clear()
        currentImageId = null
        currentImageReceivedBytes.clear()
        autoDisconnectJob?.cancel()
        _connectionState.value = GlassesConnectionState.DISCONNECTED
        onPacketLogged?.invoke("SIM", "DISCONNECTED", "", "Disconnected from simulated glasses")
    }

    override fun getMaxPayloadSize(): Int = BleConstants.MAX_WRITE_BYTES

    fun trigger5SecondDisconnect() {
        autoDisconnectJob?.cancel()
        autoDisconnectJob = scope.launch {
            delay(5000)
            disconnect()
        }
    }

    override suspend fun sendPacket(packet: ByteArray): Boolean {
        if (_connectionState.value != GlassesConnectionState.CONNECTED || packet.isEmpty()) return false

        val type = packet[0]
        val hex = PacketBuilder.toHexString(packet)
        onPacketLogged?.invoke("APP -> GLASSES", PacketTypes.getPacketName(type), hex, "")

        when (type) {
            PacketTypes.BEGIN_SYNC -> handleBeginSync(packet)
            PacketTypes.CARD -> handleCard(packet)
            PacketTypes.IMG_BEGIN -> handleImgBegin(packet)
            PacketTypes.IMG_CHUNK -> handleImgChunk(packet)
            PacketTypes.IMG_END -> handleImgEnd(packet)
            PacketTypes.END_SYNC -> handleEndSync(packet)
            PacketTypes.GET_STORAGE -> handleGetStorage()
            PacketTypes.GET_INFO -> handleGetInfo()
            else -> emitStatus(type, PacketTypes.STATUS_ERROR)
        }

        return true
    }

    private suspend fun handleBeginSync(packet: ByteArray) {
        if (packet.size < 3) {
            emitStatus(PacketTypes.BEGIN_SYNC, PacketTypes.STATUS_ERROR)
            return
        }

        val buffer = ByteBuffer.wrap(packet, 1, 2).order(ByteOrder.LITTLE_ENDIAN)
        val cardCount = buffer.getShort().toInt() and 0xFFFF

        isSyncInProgress = true
        expectedCardCount = cardCount
        stagingDeck.clear()
        currentImageId = null
        currentImageReceivedBytes.clear()

        emitStatus(PacketTypes.BEGIN_SYNC, PacketTypes.STATUS_OK)
    }

    private suspend fun handleCard(packet: ByteArray) {
        if (!isSyncInProgress || packet.size < 13) {
            emitStatus(PacketTypes.CARD, PacketTypes.STATUS_ERROR)
            return
        }

        val payload = ByteBuffer.wrap(packet, 1, packet.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        val index = payload.getShort().toInt() and 0xFFFF
        val frontImgId = payload.getInt().toLong() and 0xFFFFFFFFL
        val backImgId = payload.getInt().toLong() and 0xFFFFFFFFL

        val remainingPayload = packet.size - 1
        val folderLenCandidate = payload.get().toInt() and 0xFF

        // New format has 13 bytes of fixed payload metadata.
        val looksNew = remainingPayload >= 13
        if (looksNew) {
            val frontLen = payload.get().toInt() and 0xFF
            val backLen = payload.get().toInt() and 0xFF
            val expectedPayloadSize = 13 + folderLenCandidate + frontLen + backLen

            if (folderLenCandidate <= BleConstants.MAX_FOLDER_NAME_BYTES &&
                frontLen <= DeviceLimits.maxTextBytes &&
                backLen <= DeviceLimits.maxTextBytes &&
                index < expectedCardCount &&
                remainingPayload == expectedPayloadSize
            ) {
                val folderBytes = ByteArray(folderLenCandidate)
                payload.get(folderBytes)
                val frontBytes = ByteArray(frontLen)
                payload.get(frontBytes)
                val backBytes = ByteArray(backLen)
                payload.get(backBytes)

                val folder = String(folderBytes, Charsets.US_ASCII).ifBlank { "OllO" }
                val front = String(frontBytes, Charsets.US_ASCII)
                val back = String(backBytes, Charsets.US_ASCII)

                stagingDeck.add(SimulatedCard(index, frontImgId, backImgId, folder, front, back))
                emitStatus(PacketTypes.CARD, PacketTypes.STATUS_OK)
                return
            }
        }

        // Backward-compatible v1 card packet.
        val legacyPayload = ByteBuffer.wrap(packet, 1, packet.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        legacyPayload.position(10)
        val frontLen = legacyPayload.get().toInt() and 0xFF
        val backLen = legacyPayload.get().toInt() and 0xFF
        val expectedPayloadSize = 12 + frontLen + backLen

        if (index >= expectedCardCount ||
            frontLen > DeviceLimits.maxTextBytes ||
            backLen > DeviceLimits.maxTextBytes ||
            remainingPayload != expectedPayloadSize
        ) {
            emitStatus(PacketTypes.CARD, PacketTypes.STATUS_ERROR)
            return
        }

        val frontBytes = ByteArray(frontLen)
        legacyPayload.get(frontBytes)
        val backBytes = ByteArray(backLen)
        legacyPayload.get(backBytes)

        stagingDeck.add(
            SimulatedCard(
                index,
                frontImgId,
                backImgId,
                "OllO",
                String(frontBytes, Charsets.US_ASCII),
                String(backBytes, Charsets.US_ASCII)
            )
        )
        emitStatus(PacketTypes.CARD, PacketTypes.STATUS_OK)
    }

    private suspend fun handleImgBegin(packet: ByteArray) {
        if (!isSyncInProgress || (packet.size != 13 && packet.size != 14)) {
            emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_ERROR)
            return
        }

        val buffer = ByteBuffer.wrap(packet, 1, packet.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        val id = buffer.getInt().toLong() and 0xFFFFFFFFL
        val width = buffer.getShort().toInt() and 0xFFFF
        val height = buffer.getShort().toInt() and 0xFFFF
        val dataLen = buffer.getInt().toLong() and 0xFFFFFFFFL
        val format = if (packet.size == 14) buffer.get().toInt() and 0xFF else 0

        val valid = when (format) {
            0 -> width in 1..640 && height in 1..480 && dataLen == (((width + 7) / 8) * height).toLong() && dataLen <= 38_400L
            1 -> width in 1..320 && height in 1..240 && dataLen == width.toLong() * height.toLong() * 2L && dataLen <= 153_600L
            else -> false
        }

        if (id == 0L || !valid) {
            emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_ERROR)
            return
        }

        if (usedFlashBytes + dataLen + 5 > flashTotalBytes) {
            emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_STORAGE_FULL)
            return
        }

        if (storedImages.containsKey(id)) {
            emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_ALREADY_HAVE_IMAGE)
            return
        }

        currentImageId = id
        currentImageWidth = width
        currentImageHeight = height
        currentImageFormat = format
        currentImageDataLen = dataLen
        currentImageReceivedBytes.clear()

        emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_OK)
    }

    private fun handleImgChunk(packet: ByteArray) {
        if (!isSyncInProgress || currentImageId == null || packet.size < 5)
            return

        val buffer = ByteBuffer.wrap(packet, 1, 4).order(ByteOrder.LITTLE_ENDIAN)
        val offset = buffer.getInt().toLong() and 0xFFFFFFFFL
        val chunkLen = packet.size - 5

        if (offset != currentImageReceivedBytes.size.toLong() || offset + chunkLen > currentImageDataLen) {
            currentImageId = null
            currentImageReceivedBytes.clear()
            return
        }

        if (corruptNextChunk) {
            corruptNextChunk = false
            currentImageReceivedBytes.add(0xFF.toByte())
            return
        }

        for (i in 5 until packet.size)
            currentImageReceivedBytes.add(packet[i])
    }

    private suspend fun handleImgEnd(packet: ByteArray) {
        if (!isSyncInProgress || currentImageId == null || packet.size != 2) {
            emitStatus(PacketTypes.IMG_END, PacketTypes.STATUS_ERROR)
            return
        }

        val reportedChecksum = packet[1].toInt() and 0xFF
        if (currentImageReceivedBytes.size.toLong() != currentImageDataLen) {
            emitStatus(PacketTypes.IMG_END, PacketTypes.STATUS_ERROR)
            return
        }

        var sum = 0
        for (b in currentImageReceivedBytes)
            sum += b.toInt() and 0xFF
        val computedChecksum = sum and 0xFF

        if (computedChecksum != reportedChecksum) {
            emitStatus(PacketTypes.IMG_END, PacketTypes.STATUS_ERROR)
            return
        }

        val id = currentImageId!!
        storedImages[id] = currentImageReceivedBytes.toByteArray()
        usedFlashBytes += currentImageReceivedBytes.size + 5L

        currentImageId = null
        currentImageReceivedBytes.clear()

        emitStatus(PacketTypes.IMG_END, PacketTypes.STATUS_OK)
    }

    private suspend fun handleEndSync(packet: ByteArray) {
        if (!isSyncInProgress) {
            emitStatus(PacketTypes.END_SYNC, PacketTypes.STATUS_ERROR)
            return
        }

        if (stagingDeck.size != expectedCardCount) {
            emitStatus(PacketTypes.END_SYNC, PacketTypes.STATUS_ERROR)
            return
        }

        activeDeck.clear()
        activeDeck.addAll(stagingDeck)
        stagingDeck.clear()
        isSyncInProgress = false

        val referencedImageIds = mutableSetOf<Long>()
        for (card in activeDeck) {
            if (card.frontImgId > 0) referencedImageIds.add(card.frontImgId)
            if (card.backImgId > 0) referencedImageIds.add(card.backImgId)
        }

        val unreferenced = storedImages.keys.filter { it !in referencedImageIds }
        for (id in unreferenced) {
            val removed = storedImages.remove(id)
            if (removed != null)
                usedFlashBytes = (usedFlashBytes - removed.size - 5L).coerceAtLeast(0L)
        }

        emitStatus(PacketTypes.END_SYNC, PacketTypes.STATUS_OK)
        delay(50)
        emitStorageInfo()
    }

    private suspend fun handleGetStorage() = emitStorageInfo()

    private suspend fun handleGetInfo() {
        val infoPacket = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(PacketTypes.NOTIFY_INFO)
            put(2.toByte())
            putShort(640.toShort())
            putShort(480.toShort())
            putInt(153600)
            put(100.toByte())
            put(0x01.toByte())
        }.array()

        val parsed = PacketParser.parse(infoPacket)
        onPacketLogged?.invoke(
            "GLASSES -> APP",
            "INFO (0x82)",
            PacketBuilder.toHexString(infoPacket),
            "Firmware v2, RGB565 supported, 640x480 mono / 320x240 color"
        )
        _notifications.emit(parsed)
    }

    private suspend fun emitStatus(refType: Byte, status: Int) {
        if (dropNextAck) {
            dropNextAck = false
            onPacketLogged?.invoke("SIM", "FAULT_INJECTION", "", "Dropped acknowledgment for refType 0x%02X".format(refType))
            return
        }

        val statusPacket = byteArrayOf(PacketTypes.NOTIFY_STATUS, refType, status.toByte())
        val parsed = PacketParser.parse(statusPacket)
        val statusDesc = when (status) {
            PacketTypes.STATUS_OK -> "OK (0)"
            PacketTypes.STATUS_ERROR -> "ERROR (1)"
            PacketTypes.STATUS_ALREADY_HAVE_IMAGE -> "ALREADY_HAVE_IMAGE (2)"
            PacketTypes.STATUS_STORAGE_FULL -> "STORAGE_FULL (3)"
            else -> "$status"
        }
        onPacketLogged?.invoke(
            "GLASSES -> APP",
            "STATUS (0x80)",
            PacketBuilder.toHexString(statusPacket),
            "ref: 0x%02X -> $statusDesc".format(refType)
        )
        _notifications.emit(parsed)
    }

    private suspend fun emitStorageInfo() {
        val storagePacket = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(PacketTypes.NOTIFY_STORAGE_INFO)
            putInt((flashTotalBytes and 0xFFFFFFFFL).toInt())
            putInt((usedFlashBytes and 0xFFFFFFFFL).toInt())
        }.array()

        val parsed = PacketParser.parse(storagePacket)
        onPacketLogged?.invoke(
            "GLASSES -> APP",
            "STORAGE_INFO (0x81)",
            PacketBuilder.toHexString(storagePacket),
            "Total: ${flashTotalBytes}B, Used: ${usedFlashBytes}B"
        )
        _notifications.emit(parsed)
    }
}
