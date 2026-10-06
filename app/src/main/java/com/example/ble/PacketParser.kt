package com.example.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class IncomingPacket {
    data class Status(
        val refType: Byte,
        val status: Int
    ) : IncomingPacket() {
        val isOk: Boolean get() = status == PacketTypes.STATUS_OK
        val isAlreadyHaveImage: Boolean get() = status == PacketTypes.STATUS_ALREADY_HAVE_IMAGE
        val isStorageFull: Boolean get() = status == PacketTypes.STATUS_STORAGE_FULL
        val isError: Boolean get() = status == PacketTypes.STATUS_ERROR

        fun statusDescription(): String = when (status) {
            PacketTypes.STATUS_OK -> "OK (0)"
            PacketTypes.STATUS_ERROR -> "ERROR (1)"
            PacketTypes.STATUS_ALREADY_HAVE_IMAGE -> "ALREADY_HAVE_IMAGE (2)"
            PacketTypes.STATUS_STORAGE_FULL -> "STORAGE_FULL (3)"
            else -> "UNKNOWN ($status)"
        }
    }

    data class StorageInfo(
        val totalBytes: Long,
        val usedBytes: Long
    ) : IncomingPacket() {
        val freeBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0L)
    }

    data class Info(
        val protocolVersion: Int,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxImageBytes: Long,
        val maxTextBytes: Int,
        val flags: Int = 0 // bit0 = glasses firmware accepts RGB565 color images
    ) : IncomingPacket() {
        val supportsColor: Boolean get() = (flags and 0x01) != 0
        val supportsGray2: Boolean get() = (flags and 0x02) != 0
    }

    data class Unknown(
        val rawBytes: ByteArray
    ) : IncomingPacket() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Unknown
            return rawBytes.contentEquals(other.rawBytes)
        }

        override fun hashCode(): Int {
            return rawBytes.contentHashCode()
        }
    }
}

object PacketParser {

    fun parse(bytes: ByteArray): IncomingPacket {
        if (bytes.isEmpty()) return IncomingPacket.Unknown(bytes)

        val header = bytes[0]
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // consume header

        return try {
            when (header) {
                PacketTypes.NOTIFY_STATUS -> {
                    if (bytes.size >= 3) {
                        val refType = buffer.get()
                        val status = buffer.get().toInt() and 0xFF
                        IncomingPacket.Status(refType, status)
                    } else {
                        IncomingPacket.Unknown(bytes)
                    }
                }

                PacketTypes.NOTIFY_STORAGE_INFO -> {
                    if (bytes.size >= 9) {
                        val totalBytes = buffer.getInt().toLong() and 0xFFFFFFFFL
                        val usedBytes = buffer.getInt().toLong() and 0xFFFFFFFFL
                        IncomingPacket.StorageInfo(totalBytes, usedBytes)
                    } else {
                        IncomingPacket.Unknown(bytes)
                    }
                }

                PacketTypes.NOTIFY_INFO -> {
                    if (bytes.size >= 11) {
                        val version = buffer.get().toInt() and 0xFF
                        val maxW = buffer.getShort().toInt() and 0xFFFF
                        val maxH = buffer.getShort().toInt() and 0xFFFF
                        val maxImg = buffer.getInt().toLong() and 0xFFFFFFFFL
                        val maxTxt = buffer.get().toInt() and 0xFF
                        val flags = if (buffer.hasRemaining()) buffer.get().toInt() and 0xFF else 0
                        IncomingPacket.Info(version, maxW, maxH, maxImg, maxTxt, flags)
                    } else {
                        IncomingPacket.Unknown(bytes)
                    }
                }

                else -> IncomingPacket.Unknown(bytes)
            }
        } catch (e: Exception) {
            IncomingPacket.Unknown(bytes)
        }
    }
}
