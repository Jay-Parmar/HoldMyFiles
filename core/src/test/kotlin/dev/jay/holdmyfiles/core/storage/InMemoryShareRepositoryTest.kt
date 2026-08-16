package dev.jay.holdmyfiles.core.storage

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryShareRepositoryTest {
    @Test
    fun `add stores an enabled share and advances the version`() = runTest {
        val repository = repository()
        val root = TestRoot("content://provider/tree/photos")

        val result = repository.add("Photos", root)

        val snapshot = (result as ShareMutationResult.Changed).snapshot
        assertEquals(1L, snapshot.version.value)
        assertEquals("id-0", snapshot.shares.single().id.value)
        assertEquals("Photos", snapshot.shares.single().label)
        assertTrue(snapshot.shares.single().enabled)
        assertEquals(root, snapshot.shares.single().storageRoot)
    }

    @Test
    fun `duplicate storage roots are rejected without changing state`() = runTest {
        val repository = repository()
        val root = TestRoot("same-root")
        repository.add("First", root)

        val result = repository.add("Second", root)

        assertTrue(result is ShareMutationResult.Rejected)
        assertEquals(
            ShareMutationRejection.DuplicateRoot,
            (result as ShareMutationResult.Rejected).reason,
        )
        assertEquals(1L, result.snapshot.version.value)
        assertEquals(1, result.snapshot.shares.size)
    }

    @Test
    fun `rename and enabled changes advance only when values change`() = runTest {
        val repository = repository()
        val added = repository.add("Photos", TestRoot("photos"))
        val id = (added as ShareMutationResult.Changed).snapshot.shares.single().id

        val disabled = repository.setEnabled(id, enabled = false)
        val unchanged = repository.setEnabled(id, enabled = false)
        val renamed = repository.rename(id, "Family photos")

        assertEquals(2L, disabled.snapshot.version.value)
        assertFalse(disabled.snapshot.shares.single().enabled)
        assertTrue(unchanged is ShareMutationResult.Unchanged)
        assertEquals(2L, unchanged.snapshot.version.value)
        assertEquals(3L, renamed.snapshot.version.value)
        assertEquals("Family photos", renamed.snapshot.shares.single().label)
    }

    @Test
    fun `remove rejects missing ids without changing state`() = runTest {
        val repository = repository()

        val result = repository.remove(ShareId("missing"))

        assertTrue(result is ShareMutationResult.Rejected)
        assertEquals(
            ShareMutationRejection.MissingShare,
            (result as ShareMutationResult.Rejected).reason,
        )
        assertEquals(0L, result.snapshot.version.value)
    }

    @Test
    fun `capacity is rejected without generating another id`() = runTest {
        var generatedIds = 0
        val initial = ShareSnapshot(
            ShareSetVersion(9),
            List(64) { index -> share("share-$index", "root-$index") },
        )
        val repository = InMemoryShareRepository(
            initialSnapshot = initial,
            idGenerator = ShareIdGenerator {
                generatedIds += 1
                ShareId("unused")
            },
        )

        val result = repository.add("Overflow", TestRoot("overflow"))

        assertEquals(
            ShareMutationRejection.CapacityReached,
            (result as ShareMutationResult.Rejected).reason,
        )
        assertEquals(0, generatedIds)
        assertEquals(9L, result.snapshot.version.value)
    }

    @Test
    fun `id collisions are retried and bounded`() = runTest {
        val ids = ArrayDeque(
            listOf(ShareId("same"), ShareId("same"), ShareId("new-id")),
        )
        val initial = ShareSnapshot(
            ShareSetVersion(1),
            listOf(share("same", "existing-root")),
        )
        val repository = InMemoryShareRepository(
            initialSnapshot = initial,
            idGenerator = ShareIdGenerator { ids.removeFirst() },
        )

        val result = repository.add("New", TestRoot("new-root"))

        val snapshot = (result as ShareMutationResult.Changed).snapshot
        assertEquals(listOf("same", "new-id"), snapshot.shares.map { it.id.value })

        val exhausted = InMemoryShareRepository(
            initialSnapshot = initial,
            idGenerator = ShareIdGenerator { ShareId("same") },
        ).add("No ID", TestRoot("another-root"))
        assertEquals(
            ShareMutationRejection.IdUnavailable,
            (exhausted as ShareMutationResult.Rejected).reason,
        )
        assertEquals(1L, exhausted.snapshot.version.value)
    }

    @Test
    fun `concurrent adds commit unique versions atomically`() = runTest {
        val nextId = AtomicInteger()
        val repository = InMemoryShareRepository<TestRoot>(
            idGenerator = ShareIdGenerator { ShareId("id-${nextId.getAndIncrement()}") },
        )

        val results = List(32) { index ->
            async(Dispatchers.Default) {
                repository.add("Share $index", TestRoot("root-$index"))
            }
        }.awaitAll()

        assertTrue(results.all { result -> result is ShareMutationResult.Changed })
        val snapshot = repository.snapshot()
        assertEquals(32L, snapshot.version.value)
        assertEquals(32, snapshot.shares.map { share -> share.id }.distinct().size)
        assertEquals(32, snapshot.shares.map { share -> share.storageRoot }.distinct().size)
    }

    @Test
    fun `concurrent adds of one root commit once`() = runTest {
        val generatedIds = AtomicInteger()
        val repository = InMemoryShareRepository<TestRoot>(
            idGenerator = ShareIdGenerator {
                ShareId("id-${generatedIds.getAndIncrement()}")
            },
        )

        val results = List(16) { index ->
            async(Dispatchers.Default) {
                repository.add("Share $index", TestRoot("same-root"))
            }
        }.awaitAll()

        assertEquals(1, results.count { result -> result is ShareMutationResult.Changed })
        assertEquals(
            15,
            results.count { result ->
                result is ShareMutationResult.Rejected &&
                    result.reason == ShareMutationRejection.DuplicateRoot
            },
        )
        assertEquals(1, generatedIds.get())
        assertEquals(1L, repository.snapshot().version.value)
        assertEquals(1, repository.snapshot().shares.size)
    }

    @Test
    fun `concurrent adds competing for the last slot commit once`() = runTest {
        val nextId = AtomicInteger(63)
        val initial = ShareSnapshot(
            ShareSetVersion(11),
            List(63) { index -> share("share-$index", "root-$index") },
        )
        val repository = InMemoryShareRepository(
            initialSnapshot = initial,
            idGenerator = ShareIdGenerator {
                ShareId("share-${nextId.getAndIncrement()}")
            },
        )

        val results = listOf("last-a", "last-b").map { root ->
            async(Dispatchers.Default) {
                repository.add("Last", TestRoot(root))
            }
        }.awaitAll()

        assertEquals(1, results.count { result -> result is ShareMutationResult.Changed })
        assertEquals(
            1,
            results.count { result ->
                result is ShareMutationResult.Rejected &&
                    result.reason == ShareMutationRejection.CapacityReached
            },
        )
        assertEquals(12L, repository.snapshot().version.value)
        assertEquals(64, repository.snapshot().shares.size)
    }

    @Test
    fun `concurrent identical disables advance the version once`() = runTest {
        val initial = ShareSnapshot(
            ShareSetVersion(4),
            listOf(share("one", "root-one")),
        )
        val repository = InMemoryShareRepository(initialSnapshot = initial)

        val results = List(16) {
            async(Dispatchers.Default) {
                repository.setEnabled(ShareId("one"), enabled = false)
            }
        }.awaitAll()

        assertEquals(1, results.count { result -> result is ShareMutationResult.Changed })
        assertEquals(15, results.count { result -> result is ShareMutationResult.Unchanged })
        assertEquals(5L, repository.snapshot().version.value)
        assertFalse(repository.snapshot().shares.single().enabled)
    }

    @Test
    fun `concurrent removals advance the version once`() = runTest {
        val initial = ShareSnapshot(
            ShareSetVersion(7),
            listOf(share("one", "root-one")),
        )
        val repository = InMemoryShareRepository(initialSnapshot = initial)

        val results = List(16) {
            async(Dispatchers.Default) {
                repository.remove(ShareId("one"))
            }
        }.awaitAll()

        assertEquals(1, results.count { result -> result is ShareMutationResult.Changed })
        assertEquals(
            15,
            results.count { result ->
                result is ShareMutationResult.Rejected &&
                    result.reason == ShareMutationRejection.MissingShare
            },
        )
        assertEquals(8L, repository.snapshot().version.value)
        assertTrue(repository.snapshot().shares.isEmpty())
    }

    @Test
    fun `version overflow fails without changing state`() = runTest {
        val initial = ShareSnapshot(
            ShareSetVersion(Long.MAX_VALUE),
            listOf(share("one", "root-one")),
        )
        val repository = InMemoryShareRepository(initialSnapshot = initial)

        val failure = try {
            repository.rename(ShareId("one"), "Renamed")
            null
        } catch (cause: Throwable) {
            cause
        }

        assertTrue(failure is ArithmeticException)
        val snapshot = repository.snapshot()
        assertEquals(Long.MAX_VALUE, snapshot.version.value)
        assertEquals("Share one", snapshot.shares.single().label)
    }

    private fun repository(): InMemoryShareRepository<TestRoot> {
        var id = 0
        return InMemoryShareRepository(
            idGenerator = ShareIdGenerator { ShareId("id-${id++}") },
        )
    }

    private fun share(id: String, root: String) = SharedFolder(
        id = ShareId(id),
        label = "Share $id",
        enabled = true,
        storageRoot = TestRoot(root),
    )

    private data class TestRoot(val value: String)
}
