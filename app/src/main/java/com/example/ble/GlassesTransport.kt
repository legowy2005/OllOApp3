package com.example.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class GlassesConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

/**
 * Universal transport abstraction for Smart Glasses communication.
 * Can be backed by real BLE (Android BluetoothGatt / iOS CoreBluetooth)
 * or by SimulatedGlasses (ESP32-S3 firmware simulator).
 */
interface GlassesTransport {
    val connectionState: StateFlow<GlassesConnectionState>
    val notifications: SharedFlow<IncomingPacket>
    val deviceName: StateFlow<String?>
    val rssi: StateFlow<Int?>

    suspend fun connect(addressOrId: String? = null): Boolean
    suspend fun disconnect()
    suspend fun sendPacket(packet: ByteArray): Boolean
    fun getMaxPayloadSize(): Int
}
