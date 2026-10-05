package com.example.ble

import java.util.UUID

object BleConstants {
    const val ADVERTISED_NAME: String = "Ollo"

    val SERVICE_UUID: UUID = UUID.fromString("6f6c6c6f-0001-4000-8000-00805f9b34fb")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("6f6c6c6f-0002-4000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("6f6c6c6f-0003-4000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MAX_WRITE_BYTES: Int = 240
    const val MAX_FOLDER_NAME_BYTES: Int = 20
    const val ACK_TIMEOUT_MS: Long = 3000L
    const val GET_INFO_TIMEOUT_MS: Long = 1500L
    const val CHUNK_PACING_DELAY_MS: Long = 12L
    const val IMG_BEGIN_SETTLE_DELAY_MS: Long = 100L

    const val DEFAULT_FLASH_TOTAL_BYTES: Long = 1_441_792L
}

object PacketTypes {
    const val BEGIN_SYNC: Byte = 0x01
    const val CARD: Byte = 0x02
    const val IMG_BEGIN: Byte = 0x03
    const val IMG_CHUNK: Byte = 0x04
    const val IMG_END: Byte = 0x05
    const val END_SYNC: Byte = 0x06
    const val GET_STORAGE: Byte = 0x07
    const val GET_INFO: Byte = 0x08

    const val NOTIFY_STATUS: Byte = 0x80.toByte()
    const val NOTIFY_STORAGE_INFO: Byte = 0x81.toByte()
    const val NOTIFY_INFO: Byte = 0x82.toByte()

    const val STATUS_OK: Int = 0
    const val STATUS_ERROR: Int = 1
    const val STATUS_ALREADY_HAVE_IMAGE: Int = 2
    const val STATUS_STORAGE_FULL: Int = 3

    fun getPacketName(type: Byte): String {
        return when (type) {
            BEGIN_SYNC -> "BEGIN_SYNC (0x01)"
            CARD -> "CARD (0x02)"
            IMG_BEGIN -> "IMG_BEGIN (0x03)"
            IMG_CHUNK -> "IMG_CHUNK (0x04)"
            IMG_END -> "IMG_END (0x05)"
            END_SYNC -> "END_SYNC (0x06)"
            GET_STORAGE -> "GET_STORAGE (0x07)"
            GET_INFO -> "GET_INFO (0x08)"
            NOTIFY_STATUS -> "STATUS (0x80)"
            NOTIFY_STORAGE_INFO -> "STORAGE_INFO (0x81)"
            NOTIFY_INFO -> "INFO (0x82)"
            else -> "UNKNOWN (0x%02X)".format(type)
        }
    }
}
