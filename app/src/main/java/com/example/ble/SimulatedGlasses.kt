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

    // Persistent storage state on simulated glasses (ESP32-S3 flash)
    private var activeDeck = mutableListOf<SimulatedCard>()
    private val storedImages = mutableMapOf<Long, ByteArray>() // id -> bytes
    private var usedFlashBytes: Long = 12_288L // Initial deck storage sample

    // In-flight sync state
    private var isSyncInProgress = false
    private var expectedCardCount = 0
    private var stagingDeck = mutableListOf<SimulatedCard>()

    // In-flight image state
    private var currentImageId: Long? = null
    private var currentImageWidth: Int = 0
    private var currentImageHeight: Int = 0
    private var currentImageDataLen: Long = 0L
    private var currentImageReceivedBytes = mutableListOf<Byte>()

    // Fault injection hooks (Section 9)
    var dropNextAck: Boolean = false
    var corruptNextChunk: Boolean = false
    var autoDisconnectJob: Job? = null

    data class SimulatedCard(
        val index: Int,
        val frontImgId: Long,
        val backImgId: Long,
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

    override fun getMaxPayloadSize(): Int {
        return BleConstants.MAX_WRITE_BYTES
    }

    fun trigger5SecondDisconnect() {
        autoDisconnectJob?.cancel()
        autoDisconnectJob = scope.launch {
            delay(5000)
            disconnect()
        }
    }

    override suspend fun sendPacket(packet: ByteArray): Boolean {
        if (_connectionState.value != GlassesConnectionState.CONNECTED) return false
        if (packet.isEmpty()) return false

        val type = packet[0]
        val hex = PacketBuilder.toHexString(packet)
        val name = PacketTypes.getPacketName(type)
        onPacketLogged?.invoke("APP -> GLASSES", name, hex, "")

        // Handle packets according to Section 5.5 rules
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

        if (cardCount > 65535) {
            emitStatus(PacketTypes.BEGIN_SYNC, PacketTypes.STATUS_ERROR)
            return
        }

        // Rule 5.5: Starting a new BEGIN_SYNC discards any sync in progress
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

        val buffer = ByteBuffer.wrap(packet, 1, packet.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        val index = buffer.getShort().toInt() and 0xFFFF
        val frontImgId = buffer.getInt().toLong() and 0xFFFFFFFFL
        val backImgId = buffer.getInt().toLong() and 0xFFFFFFFFL
        val frontLen = buffer.get().toInt() and 0xFF
        val backLen = buffer.get().toInt() and 0xFF

        // Rule 5.5: index < cardCount, frontLen <= 100, backLen <= 100
        // Payload must be exactly 12 + frontLen + backLen bytes
        val expectedPayloadSize = 12 + frontLen + backLen
        if (index >= expectedCardCount || frontLen > 100 || backLen > 100 || (packet.size - 1) != expectedPayloadSize) {
            emitStatus(PacketTypes.CARD, PacketTypes.STATUS_ERROR)
            return
        }

        val frontBytes = ByteArray(frontLen)
        buffer.get(frontBytes)
        val backBytes = ByteArray(backLen)
        buffer.get(backBytes)

        val frontText = String(frontBytes, Charsets.US_ASCII)
        val backText = String(backBytes, Charsets.US_ASCII)

        stagingDeck.add(SimulatedCard(index, frontImgId, backImgId, frontText, backText))
        emitStatus(PacketTypes.CARD, PacketTypes.STATUS_OK)
    }

    private suspend fun handleImgBegin(packet: ByteArray) {
        if (!isSyncInProgress || packet.size != 13) {
            emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_ERROR)
            return
        }

        val buffer = ByteBuffer.wrap(packet, 1, 12).order(ByteOrder.LITTLE_ENDIAN)
        val id = buffer.getInt().toLong() and 0xFFFFFFFFL
        val width = buffer.getShort().toInt() and 0xFFFF
        val height = buffer.getShort().toInt() and 0xFFFF
        val dataLen = buffer.getInt().toLong() and 0xFFFFFFFFL

        val expectedDataLen = (((width + 7) / 8) * height).toLong()

        // Rule 5.5: id != 0; 1 <= width <= 320; 1 <= height <= 240; dataLen == ((width + 7) / 8) * height; dataLen <= 10240; enough space
        if (id == 0L || width !in 1..640 || height !in 1..480 || dataLen != expectedDataLen || dataLen > 38400) {
            emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_ERROR)
            return
        }

        // Check if flash has space
        if (usedFlashBytes + dataLen > flashTotalBytes) {
            emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_STORAGE_FULL)
            return
        }

        // Rule 5.4 / 5.5: If id is already stored, glasses answer status 2 (ALREADY_HAVE_IMAGE)
        if (storedImages.containsKey(id)) {
            emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_ALREADY_HAVE_IMAGE)
            return
        }

        currentImageId = id
        currentImageWidth = width
        currentImageHeight = height
        currentImageDataLen = dataLen
        currentImageReceivedBytes.clear()

        emitStatus(PacketTypes.IMG_BEGIN, PacketTypes.STATUS_OK)
    }

    private fun handleImgChunk(packet: ByteArray) {
        // IMG_CHUNK is never acknowledged
        if (!isSyncInProgress || currentImageId == null || packet.size < 5) {
            return
        }

        val buffer = ByteBuffer.wrap(packet, 1, 4).order(ByteOrder.LITTLE_ENDIAN)
        val offset = buffer.getInt().toLong() and 0xFFFFFFFFL
        val chunkLen = packet.size - 5

        // Rule 5.5: offset must equal number of bytes received so far; total must not exceed dataLen
        if (offset != currentImageReceivedBytes.size.toLong() || (offset + chunkLen) > currentImageDataLen) {
            // Bad chunk: silently abandon image
            currentImageId = null
            currentImageReceivedBytes.clear()
            return
        }

        if (corruptNextChunk) {
            corruptNextChunk = false
            currentImageReceivedBytes.add(0xFF.toByte()) // introduce deliberate byte corruption
            return
        }

        for (i in 5 until packet.size) {
            currentImageReceivedBytes.add(packet[i])
        }
    }

    private suspend fun handleImgEnd(packet: ByteArray) {
        if (!isSyncInProgress || currentImageId == null || packet.size != 2) {
            emitStatus(PacketTypes.IMG_END, PacketTypes.STATUS_ERROR)
            return
        }

        val reportedChecksum = packet[1].toInt() and 0xFF

        // Rule 5.5: total received must equal dataLen; checksum = (sum of all pixel data bytes) mod 256
        if (currentImageReceivedBytes.size.toLong() != currentImageDataLen) {
            emitStatus(PacketTypes.IMG_END, PacketTypes.STATUS_ERROR)
            return
        }

        var sum = 0
        for (b in currentImageReceivedBytes) {
            sum += (b.toInt() and 0xFF)
        }
        val computedChecksum = sum and 0xFF

        if (computedChecksum != reportedChecksum) {
            emitStatus(PacketTypes.IMG_END, PacketTypes.STATUS_ERROR)
            return
        }

        // Successfully received image
        val id = currentImageId!!
        val bytes = currentImageReceivedBytes.toByteArray()
        storedImages[id] = bytes
        usedFlashBytes += bytes.size

        currentImageId = null
        currentImageReceivedBytes.clear()

        emitStatus(PacketTypes.IMG_END, PacketTypes.STATUS_OK)
    }

    private suspend fun handleEndSync(packet: ByteArray) {
        if (!isSyncInProgress) {
            emitStatus(PacketTypes.END_SYNC, PacketTypes.STATUS_ERROR)
            return
        }

        // Rule 5.5: number of CARD packets received must equal cardCount
        if (stagingDeck.size != expectedCardCount) {
            emitStatus(PacketTypes.END_SYNC, PacketTypes.STATUS_ERROR)
            return
        }

        // Atomic deck replacement!
        activeDeck.clear()
        activeDeck.addAll(stagingDeck)
        stagingDeck.clear()
        isSyncInProgress = false

        // Delete images no card references
        val referencedImageIds = mutableSetOf<Long>()
        for (card in activeDeck) {
            if (card.frontImgId > 0) referencedImageIds.add(card.frontImgId)
            if (card.backImgId > 0) referencedImageIds.add(card.backImgId)
        }
        val unreferenced = storedImages.keys.filter { !referencedImageIds.contains(it) }
        for (unrefId in unreferenced) {
            val removedBytes = storedImages.remove(unrefId)
            if (removedBytes != null) {
                usedFlashBytes = (usedFlashBytes - removedBytes.size).coerceAtLeast(0L)
            }
        }

        // Rule 5.5: send STATUS OK, then send STORAGE_INFO
        emitStatus(PacketTypes.END_SYNC, PacketTypes.STATUS_OK)
        delay(50)
        emitStorageInfo()
    }

    private suspend fun handleGetStorage() {
        emitStorageInfo()
    }

    private suspend fun handleGetInfo() {
        val infoPacket = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(PacketTypes.NOTIFY_INFO)
            put(1.toByte()) // protocolVersion: 1
            putShort(640.toShort()) // maxWidth: 640
            putShort(480.toShort()) // maxHeight: 480
            putInt(38400) // maxImageBytes: 38400
            put(100.toByte()) // maxTextBytes: 100
            put(0.toByte()) // flags: bit0 color (not yet)
        }.array()

        val parsed = PacketParser.parse(infoPacket)
        val hex = PacketBuilder.toHexString(infoPacket)
        onPacketLogged?.invoke("GLASSES -> APP", "INFO (0x82)", hex, "Firmware v1, 640x480, 38400 img, 100 txt")
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
        val hex = PacketBuilder.toHexString(statusPacket)
        val statusDesc = when (status) {
            PacketTypes.STATUS_OK -> "OK (0)"
            PacketTypes.STATUS_ERROR -> "ERROR (1)"
            PacketTypes.STATUS_ALREADY_HAVE_IMAGE -> "ALREADY_HAVE_IMAGE (2)"
            PacketTypes.STATUS_STORAGE_FULL -> "STORAGE_FULL (3)"
            else -> "$status"
        }
        onPacketLogged?.invoke("GLASSES -> APP", "STATUS (0x80)", hex, "ref: 0x%02X -> $statusDesc".format(refType))
        _notifications.emit(parsed)
    }

    private suspend fun emitStorageInfo() {
        val storagePacket = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(PacketTypes.NOTIFY_STORAGE_INFO)
            putInt((flashTotalBytes and 0xFFFFFFFFL).toInt())
            putInt((usedFlashBytes and 0xFFFFFFFFL).toInt())
        }.array()

        val parsed = PacketParser.parse(storagePacket)
        val hex = PacketBuilder.toHexString(storagePacket)
        onPacketLogged?.invoke("GLASSES -> APP", "STORAGE_INFO (0x81)", hex, "Total: ${flashTotalBytes}B, Used: ${usedFlashBytes}B")
        _notifications.emit(parsed)
    }
}
