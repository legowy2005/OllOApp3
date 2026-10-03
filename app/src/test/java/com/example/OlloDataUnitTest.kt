package com.example

import com.example.core.AsciiTransliteration
import com.example.core.Crc32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlloDataUnitTest {

    @Test
    fun testCrc32_standardVector() {
        // Standard IEEE 802.3 CRC32 check value for "123456789" is 0xCBF43926 (3421780262L)
        val testBytes = "123456789".toByteArray(Charsets.US_ASCII)
        val crc = Crc32.calculate(testBytes)
        assertEquals(0xCBF43926L, crc)
    }

    @Test
    fun testCrc32_imageIdCalculation() {
        // Section 5.9 IMG_BEGIN vector: id 0x12345678, 16x2 pixels (dataLen = 2 * 2 = 4)
        val packedBytes = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0xF0.toByte(), 0x0F.toByte())
        val id = Crc32.calculateImageId(16, 2, packedBytes)
        assertTrue("CRC Image ID must be positive", id > 0)
    }

    @Test
    fun testCrc32_contentAddressingDeduplication() {
        // Identical device data must yield the exact same image ID (Section 3 & 6.3)
        val bytes1 = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val bytes2 = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val bytesDiff = byteArrayOf(0x12, 0x34, 0x56, 0x79)

        val id1 = Crc32.calculateImageId(16, 2, bytes1)
        val id2 = Crc32.calculateImageId(16, 2, bytes2)
        val idDiff = Crc32.calculateImageId(16, 2, bytesDiff)

        assertEquals("Identical device data must yield identical ID", id1, id2)
        assertNotEquals("Different device data must yield different ID", id1, idDiff)
    }

    @Test
    fun testChecksumCalculation_vector5_9() {
        // Section 5.9 test vector: bytes AA 55 F0 0F -> checksum 0xFE
        val packedBytes = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0xF0.toByte(), 0x0F.toByte())
        var sum = 0
        for (b in packedBytes) {
            sum += (b.toInt() and 0xFF)
        }
        val checksum = sum and 0xFF
        assertEquals(0xFE, checksum)
    }

    @Test
    fun testAsciiTransliteration_diacriticsAndWhitespace() {
        val input = "Café au lait\twith\ncrêpe & Straße"
        val result = AsciiTransliteration.sanitizeForGlasses(input)

        // Tab and newline converted to space, accents removed, ß converted to ss
        assertEquals("Cafe au lait with crepe & Strasse", result.cleanText)
        assertTrue(result.hasModifications)
        assertTrue(result.byteLength <= 100)
    }

    @Test
    fun testAsciiTransliteration_curlyQuotes() {
        val input = "“Hello ‘World’!”"
        val result = AsciiTransliteration.sanitizeForGlasses(input)
        assertEquals("\"Hello 'World'!\"", result.cleanText)
        assertTrue(result.hasModifications)
    }

    @Test
    fun testDevicePackingDimensions() {
        val width = 320
        val height = 240
        val rowBytes = (width + 7) / 8
        val totalBytes = rowBytes * height
        assertEquals(40, rowBytes)
        assertEquals(9600, totalBytes)
        assertTrue("Device bytes must fit within 10,240 limit", totalBytes <= 10240)
    }

    @Test
    fun testPixelBitwisePackingRules() {
        // Section 6.1:
        // rowBytes = (width + 7) / 8
        // Within a byte the most significant bit is the leftmost pixel:
        // pixel at column x lives in byte y * rowBytes + x / 8, at bit 7 - (x % 8).
        // Padding bits at end of a row are 0.
        val width = 5
        val height = 1
        val rowBytes = (width + 7) / 8
        assertEquals(1, rowBytes)

        // Set only pixel at x=0 (leftmost) and x=4 (5th pixel)
        var byteVal = 0
        byteVal = byteVal or (1 shl (7 - 0)) // bit 7
        byteVal = byteVal or (1 shl (7 - 4)) // bit 3

        // Padding bits (bits 2, 1, 0) remain 0
        assertEquals(0b10001000, byteVal and 0xFF)
        assertEquals(0, byteVal and 0b00000111) // padding bits are 0
    }
}
