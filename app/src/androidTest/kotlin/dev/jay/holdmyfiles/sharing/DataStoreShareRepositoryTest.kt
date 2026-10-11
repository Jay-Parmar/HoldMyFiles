package dev.jay.holdmyfiles.sharing

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jay.holdmyfiles.core.storage.ShareId
import dev.jay.holdmyfiles.core.storage.ShareIdGenerator
import dev.jay.holdmyfiles.core.storage.ShareMutationResult
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreShareRepositoryTest {
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storeFile = File(context.cacheDir, "shares-${UUID.randomUUID()}.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun addedFolder_isPersistedAndObserved() = runBlocking {
        val repository = repository()
        val root = Uri.parse("content://provider/tree/primary%3APictures")

        val result = repository.add("Pictures", root)

        assertTrue(result is ShareMutationResult.Changed)
        val snapshot = repository.snapshots.first()
        assertEquals(1, snapshot.version.value)
        assertEquals("Pictures", snapshot.shares.single().label)
        assertEquals(root, snapshot.shares.single().storageRoot)
    }

    @Test
    fun concurrentAdds_haveNoLostUpdates() = runBlocking {
        val repository = repository()

        coroutineScope {
            (1..16).map { index ->
                async(Dispatchers.Default) {
                    repository.add(
                        label = "Folder $index",
                        storageRoot = Uri.parse("content://provider/tree/root$index"),
                    )
                }
            }.awaitAll()
        }

        val snapshot = repository.snapshot()
        assertEquals(16, snapshot.version.value)
        assertEquals(16, snapshot.shares.size)
        assertEquals(16, snapshot.shares.map { it.id }.distinct().size)
    }

    private fun repository(): DataStoreShareRepository {
        val nextId = AtomicInteger()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
        return DataStoreShareRepository(
            dataStore = dataStore,
            idGenerator = ShareIdGenerator { ShareId("share_${nextId.incrementAndGet()}") },
        )
    }
}
