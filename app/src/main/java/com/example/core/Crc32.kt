package com.example.core

/**
 * Standard IEEE 802.3 table-based CRC32 implementation (polynomial 0xEDB88320).
 * Multiplatform compatible.
 */
object Crc32 {
    private val TABLE = IntArray(256) { i ->
        var entry = i
        for (j in 0 until 8) {
            entry = if ((entry and 1) != 0) {
                (entry ushr 1) xor 0xEDB88320.toInt()
            } else {
                entry ushr 1
            }
        }
        entry
    }

    /**
     * Computes the 32-bit CRC as an unsigned Long.
     */
    fun calculate(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
        var crc = 0xFFFFFFFF.toInt()
        val end = offset + length
        for (i in offset until end) {
            val byteVal = bytes[i].toInt() and 0xFF
            val tableIndex = (crc xor byteVal) and 0xFF
            crc = (crc ushr 8) xor TABLE[tableIndex]
        }
        val result = (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL
        return if (result == 0L) 1L else result
    }

    /**
     * Calculates the Image ID according to Section 6.3:
     * id = CRC32( width as u16 little-endian, height as u16 little-endian, then all packed pixel bytes )
     * If 0, use 1.
     */
    fun calculateImageId(width: Int, height: Int, packedPixelBytes: ByteArray): Long {
        val headerAndData = ByteArray(4 + packedPixelBytes.size)
        // width u16 little-endian
        headerAndData[0] = (width and 0xFF).toByte()
        headerAndData[1] = ((width shr 8) and 0xFF).toByte()
        // height u16 little-endian
        headerAndData[2] = (height and 0xFF).toByte()
        headerAndData[3] = ((height shr 8) and 0xFF).toByte()
        // packed pixel bytes
        System.arraycopy(packedPixelBytes, 0, headerAndData, 4, packedPixelBytes.size)

        return calculate(headerAndData)
    }
}
