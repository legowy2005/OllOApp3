package com.example.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/** A BLE device found while scanning. */
data class ScannedDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val isOllo: Boolean
)

@SuppressLint("MissingPermission")
class AndroidBleTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    var onPacketLogged: ((direction: String, packetName: String, hexData: String, notes: String) -> Unit)? = null
) : GlassesTransport {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val prefs = context.getSharedPreferences("ollo_ble", Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow(GlassesConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<GlassesConnectionState> = _connectionState.asStateFlow()

    private val _notifications = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<IncomingPacket> = _notifications.asSharedFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23

    /** Completed when the link is fully usable (services found + notifications enabled). */
    private var readyDeferred: CompletableDeferred<Boolean>? = null
    private var scanTimeoutJob: Job? = null

    /** Only one connect attempt at a time (auto-reconnect and manual connect must not overlap). */
    private val connectMutex = Mutex()

    /** True after the user explicitly disconnected; auto-reconnect must respect that. */
    @Volatile
    var userRequestedDisconnect: Boolean = false
        private set

    var lastAddress: String?
        get() = prefs.getString("last_address", null)
        private set(value) { prefs.edit().putString("last_address", value).apply() }

    var lastName: String?
        get() = prefs.getString("last_name", null)
        private set(value) { prefs.edit().putString("last_name", value).apply() }

    fun forgetSavedDevice() {
        prefs.edit().remove("last_address").remove("last_name").apply()
    }

    // ---------------------------------------------------------------------
    // Permissions / adapter
    // ---------------------------------------------------------------------

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothOn(): Boolean = bluetoothAdapter?.isEnabled == true

    // ---------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == BleConstants.SERVICE_UUID } == true
            val isOllo = hasService || name.equals(BleConstants.ADVERTISED_NAME, ignoreCase = true)
            // Hide anonymous devices unless they advertise our service
            if (name.isNullOrBlank() && !isOllo) return

            val device = ScannedDevice(
                address = result.device.address,
                name = name ?: BleConstants.ADVERTISED_NAME,
                rssi = result.rssi,
                isOllo = isOllo
            )
            val current = _scanResults.value.toMutableList()
            val idx = current.indexOfFirst { it.address == device.address }
            if (idx >= 0) current[idx] = device else current.add(device)
            _scanResults.value = current.sortedWith(compareByDescending<ScannedDevice> { it.isOllo }.thenByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            onPacketLogged?.invoke("BLE", "SCAN_FAILED", "", "Scan failed, error code $errorCode")
        }
    }

    fun startScan(timeoutMs: Long = 10_000L): Boolean {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return false
        if (!hasPermissions() || !isBluetoothOn()) return false
        if (_isScanning.value) return true

        _scanResults.value = emptyList()
        // No filters: some phones drop 128-bit UUID filters when the UUID sits in the scan response.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(null, settings, scanCallback)
            _isScanning.value = true
            onPacketLogged?.invoke("BLE", "SCAN_START", "", "Scanning for glasses...")
            scanTimeoutJob?.cancel()
            scanTimeoutJob = scope.launch {
                delay(timeoutMs)
                stopScan()
            }
            true
        } catch (e: Exception) {
            _isScanning.value = false
            false
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        if (!_isScanning.value) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (ignored: Exception) {}
        _isScanning.value = false
    }

    /** Scans until an Ollo device is found (or timeout) and returns its address. */
    private suspend fun findOlloAddress(timeoutMs: Long = 8_000L): String? {
        if (!startScan(timeoutMs + 1_000L)) return null
        val found = withTimeoutOrNull(timeoutMs) {
            var address: String? = null
            while (address == null) {
                address = _scanResults.value.firstOrNull { it.isOllo }?.address
                if (address == null) delay(200)
            }
            address
        }
        stopScan()
        return found
    }

    // ---------------------------------------------------------------------
    // GATT callback
    // ---------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onPacketLogged?.invoke("BLE", "LINK_UP", "", "Link up to ${gatt.device.address}, negotiating...")
                    scope.launch {
                        delay(300) // some stacks need a short pause before the first request
                        if (!gatt.requestMtu(512)) gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    onPacketLogged?.invoke("BLE", "DISCONNECTED", "", "Disconnected (status $status)")
                    _connectionState.value = GlassesConnectionState.DISCONNECTED
                    _rssi.value = null
                    readyDeferred?.complete(false)
                    cleanGatt()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                onPacketLogged?.invoke("BLE", "MTU_CHANGED", "", "MTU set to $mtu")
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = if (status == BluetoothGatt.GATT_SUCCESS) gatt.getService(BleConstants.SERVICE_UUID) else null
            if (service == null) {
                onPacketLogged?.invoke("BLE", "SERVICE_MISSING", "", "Ollo service not found (status $status)")
                readyDeferred?.complete(false)
                return
            }

            writeCharacteristic = service.getCharacteristic(BleConstants.WRITE_CHAR_UUID)?.apply {
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            notifyCharacteristic = service.getCharacteristic(BleConstants.NOTIFY_CHAR_UUID)

            val notifyChar = notifyCharacteristic
            val descriptor = notifyChar?.getDescriptor(BleConstants.CCCD_UUID)
            if (writeCharacteristic == null || notifyChar == null || descriptor == null) {
                readyDeferred?.complete(false)
                return
            }

            gatt.setCharacteristicNotification(notifyChar, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BleConstants.CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _deviceName.value = gatt.device.name ?: BleConstants.ADVERTISED_NAME
                lastAddress = gatt.device.address
                lastName = _deviceName.value
                _connectionState.value = GlassesConnectionState.CONNECTED
                onPacketLogged?.invoke("BLE", "READY", "", "Connected to ${gatt.device.address}, notifications enabled")
                readyDeferred?.complete(true)
                scope.launch {
                    while (_connectionState.value == GlassesConnectionState.CONNECTED) {
                        bluetoothGatt?.readRemoteRssi()
                        delay(5_000)
                    }
                }
            } else {
                readyDeferred?.complete(false)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BleConstants.NOTIFY_CHAR_UUID) {
                @Suppress("DEPRECATION")
                handleIncomingBytes(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == BleConstants.NOTIFY_CHAR_UUID) handleIncomingBytes(value)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _rssi.value = rssi
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val parsed = PacketParser.parse(bytes)
        val hex = PacketBuilder.toHexString(bytes)
        val name = if (bytes.isNotEmpty()) PacketTypes.getPacketName(bytes[0]) else "EMPTY"
        onPacketLogged?.invoke("GLASSES -> APP", name, hex, "")
        scope.launch { _notifications.emit(parsed) }
    }

    // ---------------------------------------------------------------------
    // GlassesTransport
    // ---------------------------------------------------------------------

    /**
     * Connects and only returns true once the link is really usable.
     * addressOrId == null -> use the last connected device, otherwise scan for an "Ollo" device.
     */
    override suspend fun connect(addressOrId: String?): Boolean = connectMutex.withLock {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled || !hasPermissions()) {
            onPacketLogged?.invoke("BLE", "CONNECT_BLOCKED", "", "Bluetooth is off or permission missing")
            return@withLock false
        }
        userRequestedDisconnect = false
        if (_connectionState.value == GlassesConnectionState.CONNECTED) return@withLock true

        _connectionState.value = GlassesConnectionState.CONNECTING
        stopScan()

        // 1) Try the explicit / last known address (Android often fails the first try with status 133)
        val knownAddress = addressOrId ?: lastAddress
        if (knownAddress != null) {
            for (attempt in 1..2) {
                if (userRequestedDisconnect) break
                if (connectOnce(knownAddress)) return@withLock true
                cleanGatt()
                delay(400)
            }
        }

        // 2) Not reachable at that address (or none saved): scan for an Ollo device and try it
        if (addressOrId == null && !userRequestedDisconnect) {
            val found = findOlloAddress()
            if (found == null) {
                onPacketLogged?.invoke("BLE", "NOT_FOUND", "", "No Ollo glasses found nearby")
            } else if (found != knownAddress || knownAddress == null) {
                if (connectOnce(found)) return@withLock true
                cleanGatt()
            }
        }

        cleanGatt()
        _connectionState.value = GlassesConnectionState.DISCONNECTED
        false
    }

    private suspend fun connectOnce(address: String): Boolean {
        return try {
            cleanGatt()
            val device: BluetoothDevice = bluetoothAdapter!!.getRemoteDevice(address)
            val deferred = CompletableDeferred<Boolean>()
            readyDeferred = deferred
            _connectionState.value = GlassesConnectionState.CONNECTING
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            withTimeoutOrNull(10_000L) { deferred.await() } == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun disconnect() {
        userRequestedDisconnect = true
        stopScan()
        bluetoothGatt?.disconnect()
        delay(100)
        cleanGatt()
        _connectionState.value = GlassesConnectionState.DISCONNECTED
    }

    override suspend fun sendPacket(packet: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = writeCharacteristic ?: return false
        if (_connectionState.value != GlassesConnectionState.CONNECTED || packet.isEmpty()) return false

        val maxBytes = getMaxPayloadSize()
        val toSend = if (packet.size <= maxBytes) packet else packet.copyOf(maxBytes)

        onPacketLogged?.invoke("APP -> GLASSES", PacketTypes.getPacketName(toSend[0]), PacketBuilder.toHexString(toSend), "")

        // Write-without-response can report "busy" if the stack is still flushing; retry briefly.
        repeat(6) { attempt ->
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ returns a BluetoothStatusCodes value, not the legacy GATT callback status.
                val result = gatt.writeCharacteristic(
                    char,
                    toSend,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                if (result != BluetoothStatusCodes.SUCCESS) {
                    onPacketLogged?.invoke(
                        "BLE",
                        "WRITE_RETRY",
                        PacketBuilder.toHexString(toSend),
                        "Write failed on attempt ${attempt + 1}/6 with status $result"
                    )
                }
                result == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                char.value = toSend
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
            if (ok) return true
            delay(8)
        }
        return false
    }

    override fun getMaxPayloadSize(): Int =
        min(negotiatedMtu - 3, BleConstants.MAX_WRITE_BYTES).coerceAtLeast(20)

    private fun cleanGatt() {
        try { bluetoothGatt?.close() } catch (ignored: Exception) {}
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        negotiatedMtu = 23
    }
}
