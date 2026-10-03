package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

@SuppressLint("MissingPermission")
class AndroidBleTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    var onPacketLogged: ((direction: String, packetName: String, hexData: String, notes: String) -> Unit)? = null
) : GlassesTransport {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(GlassesConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<GlassesConnectionState> = _connectionState.asStateFlow()

    private val _notifications = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<IncomingPacket> = _notifications.asSharedFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _deviceName.value = gatt.device.name ?: BleConstants.ADVERTISED_NAME
                    _connectionState.value = GlassesConnectionState.CONNECTED
                    onPacketLogged?.invoke("BLE", "CONNECTED", "", "Connected to ${gatt.device.address}")
                    // Request large MTU (512)
                    gatt.requestMtu(512)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = GlassesConnectionState.DISCONNECTED
                    onPacketLogged?.invoke("BLE", "DISCONNECTED", "", "Disconnected from glasses")
                    cleanGatt()
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    _connectionState.value = GlassesConnectionState.CONNECTING
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    _connectionState.value = GlassesConnectionState.DISCONNECTING
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                onPacketLogged?.invoke("BLE", "MTU_CHANGED", "", "MTU set to $mtu")
            }
            // Discover services after MTU
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(BleConstants.SERVICE_UUID)
            if (service != null) {
                writeCharacteristic = service.getCharacteristic(BleConstants.WRITE_CHAR_UUID)?.apply {
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                notifyCharacteristic = service.getCharacteristic(BleConstants.NOTIFY_CHAR_UUID)

                // Enable notifications
                notifyCharacteristic?.let { notifyChar ->
                    gatt.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(BleConstants.CCCD_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        onPacketLogged?.invoke("BLE", "CCCD_ENABLED", "", "Notifications enabled on Notify characteristic")
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BleConstants.NOTIFY_CHAR_UUID) {
                handleIncomingBytes(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleConstants.NOTIFY_CHAR_UUID) {
                handleIncomingBytes(value)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val parsed = PacketParser.parse(bytes)
        val hex = PacketBuilder.toHexString(bytes)
        val name = if (bytes.isNotEmpty()) PacketTypes.getPacketName(bytes[0]) else "EMPTY"
        onPacketLogged?.invoke("GLASSES -> APP", name, hex, "")
        scope.launch {
            _notifications.emit(parsed)
        }
    }

    override suspend fun connect(addressOrId: String?): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return false
        _connectionState.value = GlassesConnectionState.CONNECTING

        return try {
            val device: BluetoothDevice = if (addressOrId != null) {
                bluetoothAdapter.getRemoteDevice(addressOrId)
            } else {
                // If no specific address, look for already bonded/paired glasses device
                val bonded = bluetoothAdapter.bondedDevices.find { it.name == BleConstants.ADVERTISED_NAME }
                bonded ?: return false
            }

            cleanGatt()
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, true, gattCallback)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            _connectionState.value = GlassesConnectionState.DISCONNECTED
            false
        }
    }

    override suspend fun disconnect() {
        bluetoothGatt?.disconnect()
        cleanGatt()
        _connectionState.value = GlassesConnectionState.DISCONNECTED
    }

    override suspend fun sendPacket(packet: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = writeCharacteristic ?: return false
        if (_connectionState.value != GlassesConnectionState.CONNECTED) return false

        val maxBytes = getMaxPayloadSize()
        val toSend = if (packet.size <= maxBytes) packet else packet.copyOf(maxBytes)

        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val hex = PacketBuilder.toHexString(toSend)
        val name = PacketTypes.getPacketName(toSend[0])
        onPacketLogged?.invoke("APP -> GLASSES", name, hex, "")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, toSend, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            char.value = toSend
            gatt.writeCharacteristic(char)
        }
    }

    override fun getMaxPayloadSize(): Int {
        // Section 5.1: Maximum bytes per write = min(negotiatedMtu - 3, 240)
        return min(negotiatedMtu - 3, BleConstants.MAX_WRITE_BYTES).coerceAtLeast(20)
    }

    private fun cleanGatt() {
        try {
            bluetoothGatt?.close()
        } catch (ignored: Exception) {}
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
    }
}
