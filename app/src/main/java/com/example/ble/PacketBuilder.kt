package com.example.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketBuilder {

    /**
     * Type 0x01: BEGIN_SYNC
     * Payload: cardCount: u16 LE
     * Total bytes: 3
     */
    fun buildBeginSync(cardCount: Int): ByteArray {
        val buffer = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PacketTypes.BEGIN_SYNC)
        buffer.putShort((cardCount and 0xFFFF).toShort())
        return buffer.array()
    }

    /**
     * Type 0x02: CARD
     * Payload: index: u16, frontImgId: u32, backImgId: u32, frontLen: u8, backLen: u8, frontText, backText
     * Total bytes: 1 + 12 + frontLen + backLen
     */
    fun buildCard(
        index: Int,
        frontImgId: Long,
        backImgId: Long,
        frontText: String,
        backText: String
    ): ByteArray {
        val frontBytes = frontText.toByteArray(Charsets.US_ASCII)
        val backBytes = backText.toByteArray(Charsets.US_ASCII)

        val frontLen = (frontBytes.size and 0xFF).toByte()
        val backLen = (backBytes.size and 0xFF).toByte()

        val totalSize = 1 + 12 + frontBytes.size + backBytes.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(PacketTypes.CARD)
        buffer.putShort((index and 0xFFFF).toShort())
        buffer.putInt((frontImgId and 0xFFFFFFFFL).toInt())
        buffer.putInt((backImgId and 0xFFFFFFFFL).toInt())
        buffer.put(frontLen)
        buffer.put(backLen)
        buffer.put(frontBytes)
        buffer.put(backBytes)

        return buffer.array()
    }

    /**
     * Type 0x03: IMG_BEGIN
     * Payload: id: u32, width: u16, height: u16, dataLen: u32
     * Total bytes: 13
     */
    fun buildImgBegin(
        id: Long,
        width: Int,
        height: Int,
        dataLen: Long,
        format: Int = 0
    ): ByteArray {
        // format 0 (1-bit) keeps the original 13-byte packet; other formats append one format byte.
        val size = if (format == 0) 13 else 14
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PacketTypes.IMG_BEGIN)
        buffer.putInt((id and 0xFFFFFFFFL).toInt())
        buffer.putShort((width and 0xFFFF).toShort())
        buffer.putShort((height and 0xFFFF).toShort())
        buffer.putInt((dataLen and 0xFFFFFFFFL).toInt())
        if (format != 0) buffer.put((format and 0xFF).toByte())
        return buffer.array()
    }

    /**
     * Type 0x04: IMG_CHUNK
     * Payload: offset: u32, bytes...
     * Total bytes: 5 + n
     */
    fun buildImgChunk(
        offset: Long,
        data: ByteArray,
        start: Int = 0,
        length: Int = data.size
    ): ByteArray {
        val buffer = ByteBuffer.allocate(5 + length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PacketTypes.IMG_CHUNK)
        buffer.putInt((offset and 0xFFFFFFFFL).toInt())
        buffer.put(data, start, length)
        return buffer.array()
    }

    /**
     * Type 0x05: IMG_END
     * Payload: checksum: u8
     * Total bytes: 2
     */
    fun buildImgEnd(checksum: Int): ByteArray {
        val buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PacketTypes.IMG_END)
        buffer.put((checksum and 0xFF).toByte())
        return buffer.array()
    }

    /**
     * Type 0x06: END_SYNC
     * Total bytes: 1
     */
    fun buildEndSync(): ByteArray {
        return byteArrayOf(PacketTypes.END_SYNC)
    }

    /**
     * Type 0x07: GET_STORAGE
     * Total bytes: 1
     */
    fun buildGetStorage(): ByteArray {
        return byteArrayOf(PacketTypes.GET_STORAGE)
    }

    /**
     * Type 0x08: GET_INFO
     * Total bytes: 1
     */
    fun buildGetInfo(): ByteArray {
        return byteArrayOf(PacketTypes.GET_INFO)
    }

    fun toHexString(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}
