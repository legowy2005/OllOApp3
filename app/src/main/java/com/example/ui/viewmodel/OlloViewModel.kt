package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.ble.AndroidBleTransport
import com.example.ble.IncomingPacket
import com.example.ble.PacketBuilder
import com.example.ble.ScannedDevice
import com.example.core.DeviceLimits
import com.example.core.ImageProcessor
import com.example.ble.GlassesConnectionState
import com.example.ble.GlassesTransport
import com.example.ble.SimulatedGlasses
import com.example.ble.sync.SyncEngine
import com.example.ble.sync.SyncState
import com.example.core.ProcessedImageResult
import com.example.data.database.OlloDatabase
import com.example.data.entity.CardEntity
import com.example.data.entity.ImageEntity
import com.example.data.repository.CardWithFolderName
import com.example.data.repository.OlloRepository
import com.example.ui.screens.folder.CardSummary
import com.example.ui.screens.home.FolderSummary
import com.example.ui.screens.settings.BleLogEntry
import com.example.ui.screens.sync.SyncPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class OlloViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = OlloRepository(OlloDatabase.getInstance(application))
    private val syncEngine = SyncEngine(repository, viewModelScope)

    private val _selectedFolderId = MutableStateFlow<Long?>(null)
    val selectedFolderId: StateFlow<Long?> = _selectedFolderId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _storageBudgetKb = MutableStateFlow(1408) // ~1.4MB default
    val storageBudgetKb: StateFlow<Int> = _storageBudgetKb.asStateFlow()

    private val _autoReconnect = MutableStateFlow(true)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _useSimulatedGlasses = MutableStateFlow(false)
    val useSimulatedGlasses: StateFlow<Boolean> = _useSimulatedGlasses.asStateFlow()

    private val _bleLogs = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val bleLogs: StateFlow<List<BleLogEntry>> = _bleLogs.asStateFlow()

    private val _offlineEstimateBytes = MutableStateFlow(0L)
    val offlineEstimateBytes: StateFlow<Long> = _offlineEstimateBytes.asStateFlow()

    // Sync UI States
    private val _syncPhase = MutableStateFlow(SyncPhase.IDLE)
    val syncPhase: StateFlow<SyncPhase> = _syncPhase.asStateFlow()

    private val _syncProgressFraction = MutableStateFlow(0f)
    val syncProgressFraction: StateFlow<Float> = _syncProgressFraction.asStateFlow()

    private val _syncStatusMessage = MutableStateFlow("Ready to sync")
    val syncStatusMessage: StateFlow<String> = _syncStatusMessage.asStateFlow()

    private val _syncErrorMessage = MutableStateFlow<String?>(null)
    val syncErrorMessage: StateFlow<String?> = _syncErrorMessage.asStateFlow()

    private val _freeStorageBytesOnGlasses = MutableStateFlow<Long?>(null)
    val freeStorageBytesOnGlasses: StateFlow<Long?> = _freeStorageBytesOnGlasses.asStateFlow()

    /** Real flash numbers reported by the glasses: (totalBytes, usedBytes). Null when not connected. */
    private val _glassesStorage = MutableStateFlow<Pair<Long, Long>?>(null)
    val glassesStorage: StateFlow<Pair<Long, Long>?> = _glassesStorage.asStateFlow()

    private val _limitsVersion = MutableStateFlow(0)
    val limitsVersion: StateFlow<Int> = _limitsVersion.asStateFlow()

    private val previewCache = HashMap<Long, ImageBitmap>()

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    // Active Transport (either simulator or Android BLE hardware)
    val simulatedGlasses = SimulatedGlasses(
        scope = viewModelScope,
        onPacketLogged = { dir, name, hex, notes -> addBleLog(dir, name, hex, notes) }
    )

    val androidBleTransport = AndroidBleTransport(
        context = application,
        scope = viewModelScope,
        onPacketLogged = { dir, name, hex, notes -> addBleLog(dir, name, hex, notes) }
    )

    val scanResults: StateFlow<List<ScannedDevice>> = androidBleTransport.scanResults
    val isScanning: StateFlow<Boolean> = androidBleTransport.isScanning

    fun requiredBlePermissions(): Array<String> = androidBleTransport.requiredPermissions()
    fun hasBlePermissions(): Boolean = androidBleTransport.hasPermissions()
    fun isBluetoothOn(): Boolean = androidBleTransport.isBluetoothOn()
    fun savedGlassesName(): String? = androidBleTransport.lastName

    fun startScan() {
        if (_useSimulatedGlasses.value) setUseSimulatedGlasses(false)
        androidBleTransport.startScan()
    }

    fun stopScan() = androidBleTransport.stopScan()

    fun connectToDevice(address: String) {
        viewModelScope.launch {
            if (_useSimulatedGlasses.value) setUseSimulatedGlasses(false)
            androidBleTransport.stopScan()
            androidBleTransport.disconnect()
            androidBleTransport.connect(address)
        }
    }

    private var reconnectJob: kotlinx.coroutines.Job? = null

    /**
     * Keeps trying to reach the saved glasses after the link drops (power loss, out of range...).
     * Stops when connected, when the user disconnects/forgets the glasses, or auto-reconnect is off.
     */
    private fun startAutoReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var attempt = 0
            while (true) {
                val canTry = _autoReconnect.value &&
                    !_useSimulatedGlasses.value &&
                    !androidBleTransport.userRequestedDisconnect &&
                    androidBleTransport.lastAddress != null &&
                    androidBleTransport.hasPermissions() &&
                    androidBleTransport.isBluetoothOn()
                if (androidBleTransport.connectionState.value == GlassesConnectionState.CONNECTED) return@launch
                if (!canTry) {
                    // Bluetooth off / permission missing: wait and re-check unless the user opted out
                    if (!_autoReconnect.value || androidBleTransport.userRequestedDisconnect ||
                        androidBleTransport.lastAddress == null || _useSimulatedGlasses.value) return@launch
                    kotlinx.coroutines.delay(3_000)
                    continue
                }
                // Backoff: 1s, 2s, 3s, 4s, then every 5s
                kotlinx.coroutines.delay((minOf(++attempt, 5) * 1_000L))
                if (androidBleTransport.connect()) return@launch
            }
        }
    }

    fun disconnectGlasses() {
        viewModelScope.launch { getActiveTransport().disconnect() }
    }

    fun forgetGlasses() {
        reconnectJob?.cancel()
        viewModelScope.launch {
            androidBleTransport.disconnect()
            androidBleTransport.forgetSavedDevice()
            _glassesStorage.value = null
        }
    }

    /** Asks the glasses for firmware limits and real free storage. */
    fun refreshGlassesInfo() {
        viewModelScope.launch {
            val t = getActiveTransport()
            if (t.connectionState.value != GlassesConnectionState.CONNECTED) return@launch
            t.sendPacket(PacketBuilder.buildGetInfo())
            kotlinx.coroutines.delay(250)
            t.sendPacket(PacketBuilder.buildGetStorage())
        }
    }

    private fun handleGlassesNotification(packet: IncomingPacket) {
        when (packet) {
            is IncomingPacket.StorageInfo -> _glassesStorage.value = packet.totalBytes to packet.usedBytes
            is IncomingPacket.Info -> {
                DeviceLimits.updateLimits(packet.maxWidth, packet.maxHeight, packet.maxImageBytes, packet.maxTextBytes, packet.supportsColor)
                _limitsVersion.value = _limitsVersion.value + 1
            }
            else -> {}
        }
    }

    /** Preview of a stored image exactly as the glasses will show it (cached). */
    suspend fun loadImagePreview(imageId: Long): ImageBitmap? {
        previewCache[imageId]?.let { return it }
        val image = repository.getImage(imageId) ?: return null
        val bmp = ImageProcessor.createPreviewBitmap(image.deviceData, image.width, image.height, image.format).asImageBitmap()
        previewCache[imageId] = bmp
        return bmp
    }

    fun getActiveTransport(): GlassesTransport {
        return if (_useSimulatedGlasses.value) simulatedGlasses else androidBleTransport
    }

    // Folders
    val folders: StateFlow<List<FolderSummary>> =
        combine(repository.allFolders, repository.cardCountsPerFolder) { folderEntities, counts ->
            folderEntities.map { entity ->
                FolderSummary(
                    id = entity.id,
                    name = entity.name,
                    cardCount = counts[entity.id] ?: 0,
                    includeInSync = entity.includeInSync,
                    updatedAtFormatted = dateFormat.format(Date(entity.updatedAt))
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current Folder Cards
    val currentCards: StateFlow<List<CardSummary>> = _selectedFolderId.flatMapLatest { folderId ->
        if (folderId == null) {
            flowOf(emptyList())
        } else {
            repository.getCardsForFolder(folderId).map { cardEntities ->
                cardEntities.map { card ->
                    CardSummary(
                        id = card.id,
                        folderId = card.folderId,
                        sortOrder = card.sortOrder,
                        frontText = card.frontText,
                        backText = card.backText,
                        hasFrontImage = card.frontImageId != null && card.frontImageId > 0,
                        hasBackImage = card.backImageId != null && card.backImageId > 0,
                        frontImageId = card.frontImageId?.takeIf { it > 0 },
                        backImageId = card.backImageId?.takeIf { it > 0 }
                    )
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Global Search
    val searchResults: StateFlow<List<CardWithFolderName>> = _searchQuery.flatMapLatest { query ->
        if (query.isBlank()) {
            flowOf(emptyList())
        } else {
            repository.searchCardsWithFolder(query)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        refreshEstimate()

        // Learn limits + real storage as soon as any transport connects; keep them fresh afterwards
        listOf<GlassesTransport>(androidBleTransport, simulatedGlasses).forEach { transport ->
            viewModelScope.launch { transport.notifications.collect { handleGlassesNotification(it) } }
            viewModelScope.launch {
                transport.connectionState.collect { state ->
                    if (state == GlassesConnectionState.CONNECTED) {
                        kotlinx.coroutines.delay(400)
                        refreshGlassesInfo()
                    } else if (state == GlassesConnectionState.DISCONNECTED && transport === getActiveTransport()) {
                        _glassesStorage.value = null
                        if (transport === androidBleTransport) startAutoReconnect()
                    }
                }
            }
        }

        // Reconnect to the last used glasses on launch
        viewModelScope.launch {
            if (!_useSimulatedGlasses.value && _autoReconnect.value &&
                androidBleTransport.lastAddress != null &&
                androidBleTransport.hasPermissions() && androidBleTransport.isBluetoothOn()
            ) {
                androidBleTransport.connect()
            }
        }

        // Auto-connect simulator on startup
        viewModelScope.launch {
            if (_useSimulatedGlasses.value) {
                simulatedGlasses.connect()
            }
        }

        // Monitor SyncEngine State
        viewModelScope.launch {
            syncEngine.syncState.collect { state ->
                when (state) {
                    is SyncState.Idle -> {
                        _syncPhase.value = SyncPhase.IDLE
                        _syncProgressFraction.value = 0f
                        _syncStatusMessage.value = "Ready to sync"
                        _syncErrorMessage.value = null
                    }
                    is SyncState.Connecting -> {
                        _syncPhase.value = SyncPhase.CHECKING_STORAGE
                        _syncProgressFraction.value = 0.05f
                        _syncStatusMessage.value = "Connecting to glasses..."
                    }
                    is SyncState.CheckingInfo -> {
                        _syncPhase.value = SyncPhase.CHECKING_STORAGE
                        _syncProgressFraction.value = 0.10f
                        _syncStatusMessage.value = "Querying glasses firmware capabilities..."
                    }
                    is SyncState.CheckingStorage -> {
                        _syncPhase.value = SyncPhase.CHECKING_STORAGE
                        _syncProgressFraction.value = 0.15f
                        _syncStatusMessage.value = "Checking glasses storage..."
                    }
                    is SyncState.SendingCards -> {
                        _syncPhase.value = SyncPhase.SYNCING_CARDS
                        _syncProgressFraction.value = 0.2f + (0.4f * (state.current.toFloat() / state.total.coerceAtLeast(1)))
                        _syncStatusMessage.value = "Sending cards (${state.current}/${state.total})..."
                    }
                    is SyncState.SendingImages -> {
                        _syncPhase.value = SyncPhase.SYNCING_IMAGES
                        _syncProgressFraction.value = 0.6f + (0.35f * (state.current.toFloat() / state.total.coerceAtLeast(1)))
                        _syncStatusMessage.value = "Sending images (${state.current}/${state.total})..."
                    }
                    is SyncState.Finalizing -> {
                        _syncPhase.value = SyncPhase.FINALIZING
                        _syncProgressFraction.value = 0.95f
                        _syncStatusMessage.value = "Finalizing sync on glasses..."
                    }
                    is SyncState.Success -> {
                        _syncPhase.value = SyncPhase.COMPLETED
                        _syncProgressFraction.value = 1.0f
                        _syncStatusMessage.value = "Sync complete! Synced ${state.cardsSynced} cards and ${state.imagesSynced} images."
                        _freeStorageBytesOnGlasses.value = state.freeBytesOnGlasses
                    }
                    is SyncState.Failed -> {
                        _syncPhase.value = SyncPhase.FAILED
                        _syncErrorMessage.value = state.errorMessage
                        _syncStatusMessage.value = "Sync failed at ${state.stepName}"
                    }
                }
            }
        }
    }

    fun startSync() {
        val transport = getActiveTransport()
        _syncErrorMessage.value = null
        syncEngine.startSync(transport) { progress, message ->
            _syncProgressFraction.value = progress
            _syncStatusMessage.value = message
        }
    }

    fun retrySync() {
        startSync()
    }

    fun selectFolder(folderId: Long?) {
        _selectedFolderId.value = folderId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setStorageBudgetKb(kb: Int) {
        _storageBudgetKb.value = kb
    }

    fun setAutoReconnect(enabled: Boolean) {
        _autoReconnect.value = enabled
        if (enabled && !_useSimulatedGlasses.value &&
            androidBleTransport.connectionState.value == GlassesConnectionState.DISCONNECTED) {
            startAutoReconnect()
        }
    }

    fun setUseSimulatedGlasses(enabled: Boolean) {
        _useSimulatedGlasses.value = enabled
        viewModelScope.launch {
            if (enabled) {
                androidBleTransport.disconnect()
                simulatedGlasses.connect()
            } else {
                simulatedGlasses.disconnect()
                if (androidBleTransport.lastAddress != null && androidBleTransport.hasPermissions()) {
                    androidBleTransport.connect()
                }
            }
        }
    }

    fun addFolder(name: String) {
        viewModelScope.launch {
            repository.addFolder(name)
            refreshEstimate()
        }
    }

    fun renameFolder(folderId: Long, newName: String) {
        viewModelScope.launch {
            repository.renameFolder(folderId, newName)
        }
    }

    fun toggleFolderSync(folderId: Long, include: Boolean) {
        viewModelScope.launch {
            repository.setFolderIncludeInSync(folderId, include)
            refreshEstimate()
        }
    }

    fun deleteFolder(folderId: Long) {
        viewModelScope.launch {
            repository.deleteFolder(folderId)
            refreshEstimate()
        }
    }

    fun saveCard(
        folderId: Long,
        cardId: Long?,
        frontText: String,
        backText: String,
        frontImageId: Long? = null,
        backImageId: Long? = null
    ) {
        viewModelScope.launch {
            if (cardId != null && cardId > 0) {
                repository.updateCard(cardId, frontText, backText, frontImageId, backImageId)
            } else {
                repository.addCard(folderId, frontText, backText, frontImageId, backImageId)
            }
            refreshEstimate()
        }
    }

    fun deleteCard(cardId: Long) {
        viewModelScope.launch {
            repository.deleteCard(cardId)
            refreshEstimate()
        }
    }

    fun duplicateCard(cardId: Long) {
        viewModelScope.launch {
            repository.duplicateCard(cardId)
            refreshEstimate()
        }
    }

    fun moveCard(cardId: Long, targetFolderId: Long) {
        viewModelScope.launch {
            repository.moveCard(cardId, targetFolderId)
            refreshEstimate()
        }
    }

    fun reorderCards(folderId: Long, orderedCardIds: List<Long>) {
        viewModelScope.launch {
            repository.reorderCards(orderedCardIds)
        }
    }

    fun saveProcessedImage(result: ProcessedImageResult) {
        viewModelScope.launch {
            repository.saveImage(
                ImageEntity(
                    id = result.imageId,
                    width = result.deviceWidth,
                    height = result.deviceHeight,
                    deviceData = result.deviceData,
                    displayData = result.displayData,
                    format = if (result.isColor) 1 else 0
                )
            )
            refreshEstimate()
        }
    }

    fun exportCsv(folderId: Long, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val csv = repository.exportFolderCsv(folderId)
            onReady(csv)
        }
    }

    fun importCsv(folderName: String, csvContent: String, existingFolderId: Long? = null) {
        viewModelScope.launch {
            repository.importCsv(folderName, csvContent, existingFolderId)
            refreshEstimate()
        }
    }

    fun addBleLog(direction: String, packetName: String, hexData: String, notes: String = "") {
        val entry = BleLogEntry(
            timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date()),
            direction = direction,
            packetName = packetName,
            hexData = hexData,
            notes = notes
        )
        val current = _bleLogs.value.toMutableList()
        if (current.size >= 500) {
            current.removeAt(0)
        }
        current.add(entry)
        _bleLogs.value = current
    }

    fun refreshEstimate() {
        viewModelScope.launch {
            _offlineEstimateBytes.value = repository.calculateOfflineEstimate()
        }
    }
}
