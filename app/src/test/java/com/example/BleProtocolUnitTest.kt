package com.example

import com.example.ble.IncomingPacket
import com.example.ble.PacketBuilder
import com.example.ble.PacketParser
import com.example.ble.PacketTypes
import com.example.ble.SimulatedGlasses
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProtocolUnitTest {

    @Test
    fun testVector_beginSync_3cards() {
        // Section 5.9: 01 03 00
        val packet = PacketBuilder.buildBeginSync(3)
        val expected = byteArrayOf(0x01, 0x03, 0x00)
        assertArrayEquals(expected, packet)
    }

    @Test
    fun testVector_card_index0_hi_yo() {
        // Section 5.9:
        // 02 00 00  00 00 00 00  00 00 00 00  02 02  48 69  59 6F
        val packet = PacketBuilder.buildCard(
            index = 0,
            frontImgId = 0L,
            backImgId = 0L,
            frontText = "Hi",
            backText = "Yo"
        )
        val expected = byteArrayOf(
            0x02,
            0x00, 0x00, // index: 0
            0x00, 0x00, 0x00, 0x00, // frontImgId: 0
            0x00, 0x00, 0x00, 0x00, // backImgId: 0
            0x02, 0x02, // frontLen: 2, backLen: 2
            0x48, 0x69, // "Hi"
            0x59, 0x6F  // "Yo"
        )
        assertArrayEquals(expected, packet)
    }

    @Test
    fun testVector_imgBegin_16x2() {
        // Section 5.9:
        // 03  78 56 34 12  10 00  02 00  04 00 00 00
        val packet = PacketBuilder.buildImgBegin(
            id = 0x12345678L,
            width = 16,
            height = 2,
            dataLen = 4L
        )
        val expected = byteArrayOf(
            0x03,
            0x78, 0x56, 0x34, 0x12, // id: 0x12345678 LE
            0x10, 0x00,             // width: 16 (0x0010 LE)
            0x02, 0x00,             // height: 2 (0x0002 LE)
            0x04, 0x00, 0x00, 0x00  // dataLen: 4 (0x00000004 LE)
        )
        assertArrayEquals(expected, packet)
    }

    @Test
    fun testVector_imgChunk_offset0() {
        // Section 5.9:
        // 04  00 00 00 00  AA 55 F0 0F
        val data = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0xF0.toByte(), 0x0F.toByte())
        val packet = PacketBuilder.buildImgChunk(0L, data)
        val expected = byteArrayOf(
            0x04,
            0x00, 0x00, 0x00, 0x00, // offset: 0
            0xAA.toByte(), 0x55.toByte(), 0xF0.toByte(), 0x0F.toByte()
        )
        assertArrayEquals(expected, packet)
    }

    @Test
    fun testVector_imgEnd_checksumFE() {
        // Section 5.9:
        // 05  FE
        val packet = PacketBuilder.buildImgEnd(0xFE)
        val expected = byteArrayOf(0x05, 0xFE.toByte())
        assertArrayEquals(expected, packet)
    }

    @Test
    fun testVector_storageInfo_parser() {
        // Section 5.9:
        // 81  00 00 16 00  00 30 00 00
        // total 1,441,792 (0x160000), used 12,288 (0x3000)
        val raw = byteArrayOf(
            0x81.toByte(),
            0x00, 0x00, 0x16, 0x00, // total: 0x00160000 = 1,441,792
            0x00, 0x30, 0x00, 0x00  // used: 0x00003000 = 12,288
        )
        val parsed = PacketParser.parse(raw)
        assertTrue(parsed is IncomingPacket.StorageInfo)
        val storage = parsed as IncomingPacket.StorageInfo
        assertEquals(1_441_792L, storage.totalBytes)
        assertEquals(12_288L, storage.usedBytes)
        assertEquals(1_441_792L - 12_288L, storage.freeBytes)
    }

    @Test
    fun testSimulatedGlasses_fullSyncSession() = runBlocking {
        val simulator = SimulatedGlasses()
        simulator.connect()

        val received = mutableListOf<IncomingPacket>()
        val collectorJob = this.launch {
            simulator.notifications.collect {
                received.add(it)
            }
        }

        // 1. Begin sync for 1 card
        simulator.sendPacket(PacketBuilder.buildBeginSync(1))
        kotlinx.coroutines.delay(50)
        assertEquals(1, received.size)
        val status1 = received[0] as IncomingPacket.Status
        assertEquals(PacketTypes.BEGIN_SYNC, status1.refType)
        assertEquals(PacketTypes.STATUS_OK, status1.status)

        // 2. Send 1 card (Hi / Yo)
        simulator.sendPacket(PacketBuilder.buildCard(0, 0L, 0L, "Hi", "Yo"))
        kotlinx.coroutines.delay(50)
        assertEquals(2, received.size)
        val status2 = received[1] as IncomingPacket.Status
        assertEquals(PacketTypes.CARD, status2.refType)
        assertEquals(PacketTypes.STATUS_OK, status2.status)

        // 3. End sync
        simulator.sendPacket(PacketBuilder.buildEndSync())
        kotlinx.coroutines.delay(100)
        assertTrue(received.size >= 3)
        val status3 = received[2] as IncomingPacket.Status
        assertEquals(PacketTypes.END_SYNC, status3.refType)
        assertEquals(PacketTypes.STATUS_OK, status3.status)

        collectorJob.cancel()
        simulator.disconnect()
    }
}
