package com.example.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketBuilder {

    fun buildBeginSync(cardCount: Int): ByteArray {
        val buffer = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PacketTypes.BEGIN_SYNC)
        buffer.putShort((cardCount and 0xFFFF).toShort())
        return buffer.array()
    }

    /**
     * CARD v1 (folderName empty):
     * [type][index u16][frontImg u32][backImg u32][frontLen u8][backLen u8][front][back]
     *
     * CARD v2 (folderName present):
     * [type][index u16][frontImg u32][backImg u32][folderLen u8][frontLen u8][backLen u8][folder][front][back]
     *
     * Keeping the empty-folder case in the old layout preserves the existing unit vectors.
     */
    fun buildCard(
        index: Int,
        frontImgId: Long,
        backImgId: Long,
        frontText: String,
        backText: String,
        folderName: String = ""
    ): ByteArray {
        val frontBytes = frontText.toByteArray(Charsets.US_ASCII)
        val backBytes = backText.toByteArray(Charsets.US_ASCII)
        val folderBytes = folderName
            .take(BleConstants.MAX_FOLDER_NAME_BYTES)
            .toByteArray(Charsets.US_ASCII)

        return if (folderBytes.isEmpty()) {
            val buffer = ByteBuffer
                .allocate(1 + 12 + frontBytes.size + backBytes.size)
                .order(ByteOrder.LITTLE_ENDIAN)

            buffer.put(PacketTypes.CARD)
            buffer.putShort((index and 0xFFFF).toShort())
            buffer.putInt((frontImgId and 0xFFFFFFFFL).toInt())
            buffer.putInt((backImgId and 0xFFFFFFFFL).toInt())
            buffer.put((frontBytes.size and 0xFF).toByte())
            buffer.put((backBytes.size and 0xFF).toByte())
            buffer.put(frontBytes)
            buffer.put(backBytes)
            buffer.array()
        } else {
            val buffer = ByteBuffer
                .allocate(1 + 13 + folderBytes.size + frontBytes.size + backBytes.size)
                .order(ByteOrder.LITTLE_ENDIAN)

            buffer.put(PacketTypes.CARD)
            buffer.putShort((index and 0xFFFF).toShort())
            buffer.putInt((frontImgId and 0xFFFFFFFFL).toInt())
            buffer.putInt((backImgId and 0xFFFFFFFFL).toInt())
            buffer.put((folderBytes.size and 0xFF).toByte())
            buffer.put((frontBytes.size and 0xFF).toByte())
            buffer.put((backBytes.size and 0xFF).toByte())
            buffer.put(folderBytes)
            buffer.put(frontBytes)
            buffer.put(backBytes)
            buffer.array()
        }
    }

    /**
     * IMG_BEGIN payload:
     * format 0 = 1-bit packed (type + 12-byte payload = 13 total)
     * format 1 = RGB565 little-endian (type + 13-byte payload = 14 total)
     */
    fun buildImgBegin(
        id: Long,
        width: Int,
        height: Int,
        dataLen: Long,
        format: Int = 0
    ): ByteArray {
        val size = if (format == 0) 13 else 14
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PacketTypes.IMG_BEGIN)
        buffer.putInt((id and 0xFFFFFFFFL).toInt())
        buffer.putShort((width and 0xFFFF).toShort())
        buffer.putShort((height and 0xFFFF).toShort())
        buffer.putInt((dataLen and 0xFFFFFFFFL).toInt())
        if (format != 0)
            buffer.put((format and 0xFF).toByte())
        return buffer.array()
    }

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

    fun buildImgEnd(checksum: Int): ByteArray {
        val buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PacketTypes.IMG_END)
        buffer.put((checksum and 0xFF).toByte())
        return buffer.array()
    }

    fun buildEndSync(): ByteArray = byteArrayOf(PacketTypes.END_SYNC)

    fun buildGetStorage(): ByteArray = byteArrayOf(PacketTypes.GET_STORAGE)

    fun buildGetInfo(): ByteArray = byteArrayOf(PacketTypes.GET_INFO)

    fun toHexString(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}
