package dev.jay.holdmyfiles.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PinRateLimiterTest {
    private val clock = MutableClock()

    @Test
    fun `limits repeated attempts from one peer`() {
        val limiter = limiter(peerCapacity = 2, peerRefillMillis = 10)

        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.2"))
        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.2"))
        assertEquals(RateLimitDecision.Rejected(10), limiter.tryAcquire("192.168.1.2"))

        clock.nowMillis = 9
        assertEquals(RateLimitDecision.Rejected(1), limiter.tryAcquire("192.168.1.2"))
        clock.nowMillis = 10
        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.2"))
    }

    @Test
    fun `keeps peer limits independent`() {
        val limiter = limiter(peerCapacity = 1)

        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.2"))
        assertEquals(RateLimitDecision.Rejected(10), limiter.tryAcquire("192.168.1.2"))
        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.3"))
    }

    @Test
    fun `limits attempts globally across peers`() {
        val limiter = limiter(globalCapacity = 2, globalRefillMillis = 20)

        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.2"))
        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.3"))
        assertEquals(RateLimitDecision.Rejected(20), limiter.tryAcquire("192.168.1.4"))
    }

    @Test
    fun `bounds tracked peer state`() {
        val limiter = limiter(peerCapacity = 1, maxTrackedPeers = 2)

        limiter.tryAcquire("192.168.1.2")
        limiter.tryAcquire("192.168.1.3")
        limiter.tryAcquire("192.168.1.4")

        assertEquals(2, limiter.trackedPeerCount())
    }

    @Test
    fun `rejects attempts when the clock moves backward`() {
        val limiter = limiter()
        clock.nowMillis = 10
        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.2"))

        clock.nowMillis = 9

        assertEquals(RateLimitDecision.Rejected(1), limiter.tryAcquire("192.168.1.2"))
    }

    @Test
    fun `clear restores initial limits`() {
        val limiter = limiter(peerCapacity = 1, globalCapacity = 1)
        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.2"))
        assertEquals(RateLimitDecision.Rejected(10), limiter.tryAcquire("192.168.1.2"))

        limiter.clear()

        assertEquals(RateLimitDecision.Allowed, limiter.tryAcquire("192.168.1.2"))
    }

    private fun limiter(
        peerCapacity: Int = 5,
        peerRefillMillis: Long = 10,
        globalCapacity: Int = 100,
        globalRefillMillis: Long = 10,
        maxTrackedPeers: Int = 10,
    ): PinRateLimiter = PinRateLimiter(
        clock = clock,
        peerCapacity = peerCapacity,
        peerRefillMillis = peerRefillMillis,
        globalCapacity = globalCapacity,
        globalRefillMillis = globalRefillMillis,
        maxTrackedPeers = maxTrackedPeers,
    )

    private class MutableClock(
        var nowMillis: Long = 0,
    ) : MonotonicClock {
        override fun nowMillis(): Long = nowMillis
    }
}
