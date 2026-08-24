package dev.jay.holdmyfiles.storage

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputStreamReadLeaseTest {
    @Test
    fun `closing more than once closes the descriptor stream once`() {
        val stream = CloseCountingInputStream()
        val lease = InputStreamReadLease(stream, Dispatchers.Unconfined)

        lease.close()
        lease.close()

        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `cancelling a blocked read closes and unblocks the stream`() = runBlocking {
        val stream = BlockingInputStream()
        val lease = InputStreamReadLease(stream, Dispatchers.IO)
        val readJob = launch(Dispatchers.Default) {
            lease.read(ByteArray(1), 0, 1)
        }

        assertTrue(stream.readStarted.await(2, TimeUnit.SECONDS))
        readJob.cancelAndJoin()

        assertEquals(1, stream.closeCount)
    }

    private class CloseCountingInputStream : ByteArrayInputStream(byteArrayOf(1)) {
        var closeCount = 0

        override fun close() {
            closeCount += 1
            super.close()
        }
    }

    private class BlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        @Volatile
        var closeCount = 0

        override fun read(): Int = error("Bulk read expected")

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            readStarted.countDown()
            closed.await()
            return -1
        }

        override fun close() {
            closeCount += 1
            closed.countDown()
        }
    }
}
