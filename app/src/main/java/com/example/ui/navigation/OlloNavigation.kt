package com.example.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.LocalImagePreviewLoader
import com.example.ui.screens.card.CardEditorScreen
import com.example.ui.screens.folder.FolderScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.image.ImageEditorScreen
import com.example.ui.screens.preview.PhonePreviewScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.sync.SyncPhase
import com.example.ui.screens.sync.SyncScreen
import com.example.ui.viewmodel.OlloViewModel

sealed class OlloDestination {
    data object Home : OlloDestination()
    data class Folder(val folderId: Long, val folderName: String) : OlloDestination()
    data class CardEditor(val folderId: Long, val cardId: Long? = null) : OlloDestination()
    data class ImageEditor(val folderId: Long, val cardId: Long?, val isFront: Boolean) : OlloDestination()
    data object Sync : OlloDestination()
    data object Settings : OlloDestination()
    data class Preview(val folderId: Long, val folderName: String) : OlloDestination()
}

@Composable
fun OlloAppNavigation(
    viewModel: OlloViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    // Navigation back stack
    val backStack = remember { mutableStateListOf<OlloDestination>(OlloDestination.Home) }
    val currentDestination = backStack.lastOrNull() ?: OlloDestination.Home

    fun navigateTo(dest: OlloDestination) {
        backStack.add(dest)
    }

    fun navigateBack(): Boolean {
        return if (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
            true
        } else {
            false
        }
    }

    // Reactive State from Room Database & ViewModel
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val currentCards by viewModel.currentCards.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val storageBudgetKb by viewModel.storageBudgetKb.collectAsStateWithLifecycle()
    val autoReconnect by viewModel.autoReconnect.collectAsStateWithLifecycle()
    val useSimulatedGlasses by viewModel.useSimulatedGlasses.collectAsStateWithLifecycle()
    val bleLogs by viewModel.bleLogs.collectAsStateWithLifecycle()
    val offlineEstimateBytes by viewModel.offlineEstimateBytes.collectAsStateWithLifecycle()

    val syncPhase by viewModel.syncPhase.collectAsStateWithLifecycle()
    val syncProgressFraction by viewModel.syncProgressFraction.collectAsStateWithLifecycle()
    val syncStatusMessage by viewModel.syncStatusMessage.collectAsStateWithLifecycle()
    val syncErrorMessage by viewModel.syncErrorMessage.collectAsStateWithLifecycle()
    val freeStorageBytesOnGlasses by viewModel.freeStorageBytesOnGlasses.collectAsStateWithLifecycle()

    val activeTransport = viewModel.getActiveTransport()
    val transportState by activeTransport.connectionState.collectAsStateWithLifecycle()
    val isConnected = transportState == com.example.ble.GlassesConnectionState.CONNECTED
    val deviceName by activeTransport.deviceName.collectAsStateWithLifecycle()
    val rssi by activeTransport.rssi.collectAsStateWithLifecycle()

    val glassesStorage by viewModel.glassesStorage.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    // When connected, show the glasses' REAL storage instead of the offline estimate
    val realStorage = if (isConnected) glassesStorage else null
    val totalStorageBytes = realStorage?.first ?: (storageBudgetKb * 1024L)
    val shownUsedStorageBytes = realStorage?.second ?: offlineEstimateBytes
    val shownFreeOnGlasses = realStorage?.let { (it.first - it.second).coerceAtLeast(0L) } ?: freeStorageBytesOnGlasses

    // Card editor drafts: keep typed text when jumping to the image editor and back
    var draftFrontText by remember { mutableStateOf<String?>(null) }
    var draftBackText by remember { mutableStateOf<String?>(null) }

    // Temporary image attachment holding for active card editor
    var pendingFrontImageId by remember { mutableStateOf<Long?>(null) }
    var pendingBackImageId by remember { mutableStateOf<Long?>(null) }

    // CSV export state
    var exportedCsvText by remember { mutableStateOf<String?>(null) }

    // Back handler for Android system back button
    BackHandler(enabled = backStack.size > 1) {
        navigateBack()
    }

    val imagePreviewLoader: suspend (Long) -> androidx.compose.ui.graphics.ImageBitmap? = remember(viewModel) {
        val loader: suspend (Long) -> androidx.compose.ui.graphics.ImageBitmap? = { id -> viewModel.loadImagePreview(id) }
        loader
    }
    CompositionLocalProvider(LocalImagePreviewLoader provides imagePreviewLoader) {
    AnimatedContent(
        targetState = currentDestination,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "nav_transition"
    ) { destination ->
        when (destination) {
            is OlloDestination.Home -> {
                viewModel.selectFolder(null)
                HomeScreen(
                    folders = folders,
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    isConnected = isConnected,
                    usedStorageBytes = shownUsedStorageBytes,
                    totalStorageBytes = totalStorageBytes,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onFolderClick = { id, name ->
                        viewModel.selectFolder(id)
                        navigateTo(OlloDestination.Folder(id, name))
                    },
                    onSearchResultClick = { cardId, folderId, folderName ->
                        viewModel.selectFolder(folderId)
                        navigateTo(OlloDestination.Folder(folderId, folderName))
                    },
                    onAddFolder = { name ->
                        viewModel.addFolder(name)
                    },
                    onImportCsvNewFolder = { folderName, csvContent ->
                        viewModel.importCsv(folderName, csvContent)
                    },
                    onSyncClick = {
                        navigateTo(OlloDestination.Sync)
                    },
                    onSettingsClick = {
                        navigateTo(OlloDestination.Settings)
                    },
                    modifier = modifier
                )
            }

            is OlloDestination.Folder -> {
                viewModel.selectFolder(destination.folderId)
                val currentFolder = folders.find { it.id == destination.folderId }
                val includeInSync = currentFolder?.includeInSync ?: true

                FolderScreen(
                    folderId = destination.folderId,
                    folderName = destination.folderName,
                    cards = currentCards,
                    allFolders = folders,
                    includeInSync = includeInSync,
                    onBackClick = { navigateBack() },
                    onAddCardClick = {
                        pendingFrontImageId = null
                        pendingBackImageId = null
                        draftFrontText = null
                        draftBackText = null
                        navigateTo(OlloDestination.CardEditor(destination.folderId))
                    },
                    onEditCardClick = { cardId ->
                        val card = currentCards.find { it.id == cardId }
                        // Use the card's REAL image ids (a fake id here used to wipe the image on save)
                        pendingFrontImageId = card?.frontImageId
                        pendingBackImageId = card?.backImageId
                        draftFrontText = null
                        draftBackText = null
                        navigateTo(OlloDestination.CardEditor(destination.folderId, cardId))
                    },
                    onDuplicateCard = { cardId ->
                        viewModel.duplicateCard(cardId)
                    },
                    onDeleteCard = { cardId ->
                        viewModel.deleteCard(cardId)
                    },
                    onMoveCard = { cardId, targetFolderId ->
                        viewModel.moveCard(cardId, targetFolderId)
                    },
                    onReorderCards = { orderedIds ->
                        viewModel.reorderCards(destination.folderId, orderedIds)
                    },
                    onRenameFolder = { newName ->
                        viewModel.renameFolder(destination.folderId, newName)
                    },
                    onDeleteFolder = {
                        viewModel.deleteFolder(destination.folderId)
                        navigateBack()
                    },
                    onToggleIncludeInSync = { toggle ->
                        viewModel.toggleFolderSync(destination.folderId, toggle)
                    },
                    onExportCsv = {
                        viewModel.exportCsv(destination.folderId) { csv ->
                            exportedCsvText = csv
                        }
                    },
                    onImportCsv = { csv ->
                        viewModel.importCsv(destination.folderName, csv, destination.folderId)
                    },
                    onPreviewClick = {
                        navigateTo(OlloDestination.Preview(destination.folderId, destination.folderName))
                    },
                    exportedCsvContent = exportedCsvText,
                    onDismissExportDialog = { exportedCsvText = null },
                    modifier = modifier
                )
            }

            is OlloDestination.CardEditor -> {
                val existingCard = destination.cardId?.let { cid -> currentCards.find { it.id == cid } }

                CardEditorScreen(
                    initialFrontText = draftFrontText ?: existingCard?.frontText ?: "",
                    initialBackText = draftBackText ?: existingCard?.backText ?: "",
                    hasFrontImage = pendingFrontImageId != null,
                    hasBackImage = pendingBackImageId != null,
                    frontImageId = pendingFrontImageId,
                    backImageId = pendingBackImageId,
                    onSave = { front, back ->
                        viewModel.saveCard(
                            folderId = destination.folderId,
                            cardId = destination.cardId,
                            frontText = front,
                            backText = back,
                            frontImageId = pendingFrontImageId,
                            backImageId = pendingBackImageId
                        )
                        pendingFrontImageId = null
                        pendingBackImageId = null
                        draftFrontText = null
                        draftBackText = null
                        navigateBack()
                    },
                    onBackClick = {
                        pendingFrontImageId = null
                        pendingBackImageId = null
                        draftFrontText = null
                        draftBackText = null
                        navigateBack()
                    },
                    onOpenImagePicker = { isFront, frontText, backText ->
                        draftFrontText = frontText
                        draftBackText = backText
                        navigateTo(OlloDestination.ImageEditor(destination.folderId, destination.cardId, isFront))
                    },
                    modifier = modifier
                )
            }

            is OlloDestination.ImageEditor -> {
                ImageEditorScreen(
                    onBackClick = { navigateBack() },
                    onSaveImage = { processedResult ->
                        viewModel.saveProcessedImage(processedResult)
                        if (destination.isFront) {
                            pendingFrontImageId = processedResult.imageId
                        } else {
                            pendingBackImageId = processedResult.imageId
                        }
                        navigateBack()
                    },
                    modifier = modifier
                )
            }

            is OlloDestination.Sync -> {
                val includedFolders = folders.filter { it.includeInSync }
                val totalCards = includedFolders.sumOf { it.cardCount }

                SyncScreen(
                    isConnected = isConnected,
                    deviceName = deviceName ?: if (useSimulatedGlasses) "Simulated Ollo Glasses" else "Ollo glasses",
                    rssi = rssi,
                    usedStorageBytes = offlineEstimateBytes,
                    totalStorageBytes = totalStorageBytes,
                    freeStorageBytes = shownFreeOnGlasses,
                    includedFolders = includedFolders.map { it.name },
                    totalCardsCount = totalCards,
                    syncPhase = syncPhase,
                    progressFraction = syncProgressFraction,
                    statusMessage = syncStatusMessage,
                    errorMessage = syncErrorMessage,
                    onBackClick = { navigateBack() },
                    onStartSync = {
                        viewModel.startSync()
                    },
                    onRetrySync = {
                        viewModel.retrySync()
                    },
                    onPairNewDevice = {
                        navigateTo(OlloDestination.Settings)
                    },
                    modifier = modifier
                )
            }

            is OlloDestination.Settings -> {
                SettingsScreen(
                    storageBudgetKb = storageBudgetKb,
                    autoReconnect = autoReconnect,
                    useSimulatedGlasses = useSimulatedGlasses,
                    packetLogs = bleLogs,
                    isConnected = isConnected,
                    connectedDeviceName = deviceName,
                    scanResults = scanResults,
                    isScanning = isScanning,
                    requiredPermissions = viewModel.requiredBlePermissions(),
                    hasPermissions = { viewModel.hasBlePermissions() },
                    isBluetoothOn = { viewModel.isBluetoothOn() },
                    onStartScan = { viewModel.startScan() },
                    onStopScan = { viewModel.stopScan() },
                    onConnectDevice = { viewModel.connectToDevice(it) },
                    onDisconnect = { viewModel.disconnectGlasses() },
                    onUpdateBudgetKb = { viewModel.setStorageBudgetKb(it) },
                    onToggleAutoReconnect = { viewModel.setAutoReconnect(it) },
                    onToggleSimulatedGlasses = {
                        viewModel.setUseSimulatedGlasses(it)
                    },
                    onForgetGlasses = {
                        viewModel.forgetGlasses()
                    },
                    onBackClick = { navigateBack() },
                    modifier = modifier
                )
            }

            is OlloDestination.Preview -> {
                PhonePreviewScreen(
                    folderName = destination.folderName,
                    cards = currentCards,
                    onBackClick = { navigateBack() },
                    modifier = modifier
                )
            }
        }
    }
    }
}
