package dev.jay.holdmyfiles.core.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedReadOnlyStorageGatewayTest {
    @Test
    fun `rejects an open when every read permit is in use`() = runTest {
        val delegate = FakeGateway(
            StorageOutcome.Ok(openedFile()),
            StorageOutcome.Ok(openedFile()),
        )
        val gateway = BoundedReadOnlyStorageGateway(delegate, maxConcurrentReads = 1)

        val first = gateway.openFile(TestRoot, FileNode)
        val second = gateway.openFile(TestRoot, FileNode)

        assertTrue(first is StorageOutcome.Ok)
        assertEquals(StorageOutcome.Busy, second)
        assertEquals(1, delegate.openCalls)
    }

    @Test
    fun `closing content releases one permit exactly once`() = runTest {
        val delegate = FakeGateway(
            StorageOutcome.Ok(openedFile()),
            StorageOutcome.Ok(openedFile()),
            StorageOutcome.Ok(openedFile()),
        )
        val gateway = BoundedReadOnlyStorageGateway(delegate, maxConcurrentReads = 1)
        val first = (gateway.openFile(TestRoot, FileNode) as StorageOutcome.Ok).value

        first.close()
        first.close()
        val second = gateway.openFile(TestRoot, FileNode)
        val third = gateway.openFile(TestRoot, FileNode)

        assertTrue(second is StorageOutcome.Ok)
        assertEquals(StorageOutcome.Busy, third)
        assertEquals(2, delegate.openCalls)
    }

    @Test
    fun `failed opens release their permit`() = runTest {
        val delegate = FakeGateway(
            StorageOutcome.Missing,
            StorageOutcome.Ok(openedFile()),
        )
        val gateway = BoundedReadOnlyStorageGateway(delegate, maxConcurrentReads = 1)

        assertEquals(StorageOutcome.Missing, gateway.openFile(TestRoot, FileNode))
        assertTrue(gateway.openFile(TestRoot, FileNode) is StorageOutcome.Ok)
    }

    @Test
    fun `cancelled opens release their permit`() = runTest {
        val delegate = FakeGateway(
            CancellationException("cancelled"),
            StorageOutcome.Ok(openedFile()),
        )
        val gateway = BoundedReadOnlyStorageGateway(delegate, maxConcurrentReads = 1)

        try {
            gateway.openFile(TestRoot, FileNode)
        } catch (_: CancellationException) {
        }

        assertTrue(gateway.openFile(TestRoot, FileNode) is StorageOutcome.Ok)
    }

    private fun openedFile(): OpenedFile = OpenedFile("file.txt", 1, EmptyReadLease())

    private data object TestRoot

    private data object TestReference

    private companion object {
        val FileNode = StorageNode(TestReference, "file.txt", StorageNodeKind.File, 1)
    }

    private class EmptyReadLease : ReadLease {
        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int = -1

        override fun close() = Unit
    }

    private class FakeGateway(
        vararg outcomes: Any,
    ) : ReadOnlyStorageGateway<TestRoot, TestReference> {
        private val outcomes = ArrayDeque(outcomes.toList())
        var openCalls = 0

        override suspend fun root(
            storageRoot: TestRoot,
        ): StorageOutcome<StorageNode<TestReference>> = error("Not used")

        override suspend fun listChildren(
            storageRoot: TestRoot,
            directory: StorageNode<TestReference>,
        ): StorageOutcome<DirectoryListing<TestReference>> = error("Not used")

        @Suppress("UNCHECKED_CAST")
        override suspend fun openFile(
            storageRoot: TestRoot,
            file: StorageNode<TestReference>,
        ): StorageOutcome<OpenedFile> {
            openCalls += 1
            return when (val outcome = outcomes.removeFirst()) {
                is Throwable -> throw outcome
                else -> outcome as StorageOutcome<OpenedFile>
            }
        }
    }
}
