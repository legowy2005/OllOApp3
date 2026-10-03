package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ble.SimulatedGlasses
import com.example.ble.sync.SyncEngine
import com.example.ble.sync.SyncState
import com.example.data.database.OlloDatabase
import com.example.data.repository.OlloRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineUnitTest {

    private lateinit var database: OlloDatabase
    private lateinit var repository: OlloRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = OlloDatabase.getInstance(context)
        repository = OlloRepository(database)
    }

    @Test
    fun testSyncEngine_happyPath() = runBlocking {
        val syncEngine = SyncEngine(repository, this)

        // Add a test folder with cards
        val folderId = repository.addFolder("Test Sync Folder")
        repository.addCard(folderId, "Front 1", "Back 1")
        repository.addCard(folderId, "Front 2", "Back 2")

        val simulatedGlasses = SimulatedGlasses(scope = this)
        simulatedGlasses.connect()

        syncEngine.startSync(simulatedGlasses)

        // Wait until sync is completed or failed
        val finalState = syncEngine.syncState.first {
            it is SyncState.Success || it is SyncState.Failed
        }

        assertTrue("Sync should succeed, but was: $finalState", finalState is SyncState.Success)
        val success = finalState as SyncState.Success
        assertTrue(success.cardsSynced >= 2)
        assertTrue(success.freeBytesOnGlasses > 0)
    }

    @Test
    fun testSyncEngine_storageExceeded() = runBlocking {
        val syncEngine = SyncEngine(repository, this)

        // Add at least one card so estimate is > 10 bytes
        val folderId = repository.addFolder("Storage Test")
        repository.addCard(folderId, "Question", "Answer")

        // Simulator with tiny flash (only 10 bytes)
        val tinySimulator = SimulatedGlasses(scope = this, flashTotalBytes = 10L)
        tinySimulator.connect()

        syncEngine.startSync(tinySimulator)

        val finalState = syncEngine.syncState.first {
            it is SyncState.Success || it is SyncState.Failed
        }

        assertTrue("Sync should fail when flash storage is exceeded", finalState is SyncState.Failed)
        val failed = finalState as SyncState.Failed
        assertEquals("Storage Check", failed.stepName)
        assertTrue(failed.errorMessage.contains("Deck is too large for the glasses' remaining storage"))
    }
}
