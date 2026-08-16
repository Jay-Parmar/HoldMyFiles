package dev.jay.holdmyfiles.core.security

sealed interface RateLimitDecision {
    data object Allowed : RateLimitDecision

    data class Rejected(
        val retryAfterMillis: Long,
    ) : RateLimitDecision {
        init {
            require(retryAfterMillis > 0)
        }
    }
}

class PinRateLimiter(
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val peerCapacity: Int = 5,
    private val peerRefillMillis: Long = 30_000,
    private val globalCapacity: Int = 20,
    private val globalRefillMillis: Long = 5_000,
    private val maxTrackedPeers: Int = 256,
) {
    private val peers = LinkedHashMap<String, TokenBucket>(16, 0.75f, true)
    private var global = TokenBucket(globalCapacity, globalRefillMillis, clock.nowMillis())

    init {
        require(peerCapacity > 0)
        require(peerRefillMillis > 0)
        require(globalCapacity > 0)
        require(globalRefillMillis > 0)
        require(maxTrackedPeers > 0)
    }

    @Synchronized
    fun tryAcquire(peerAddress: String): RateLimitDecision {
        require(peerAddress.isNotBlank() && peerAddress.length <= MAX_PEER_ADDRESS_LENGTH)
        val nowMillis = clock.nowMillis()
        val globalWait = global.retryAfterMillis(nowMillis)
        if (globalWait > 0) {
            return RateLimitDecision.Rejected(globalWait)
        }

        val peer = peers[peerAddress] ?: createPeer(peerAddress, nowMillis)
        val peerWait = peer.retryAfterMillis(nowMillis)
        if (peerWait > 0) {
            return RateLimitDecision.Rejected(peerWait)
        }

        global.consume()
        peer.consume()
        return RateLimitDecision.Allowed
    }

    @Synchronized
    fun clear() {
        peers.clear()
        global = TokenBucket(globalCapacity, globalRefillMillis, clock.nowMillis())
    }

    @Synchronized
    internal fun trackedPeerCount(): Int = peers.size

    private fun createPeer(peerAddress: String, nowMillis: Long): TokenBucket {
        if (peers.size >= maxTrackedPeers) {
            val oldestPeer = peers.entries.iterator()
            if (oldestPeer.hasNext()) {
                oldestPeer.next()
                oldestPeer.remove()
            }
        }

        return TokenBucket(peerCapacity, peerRefillMillis, nowMillis).also { peer ->
            peers[peerAddress] = peer
        }
    }

    private companion object {
        const val MAX_PEER_ADDRESS_LENGTH = 64
    }

    private class TokenBucket(
        private val capacity: Int,
        private val refillMillis: Long,
        startedAtMillis: Long,
    ) {
        private var tokens = capacity
        private var lastRefillMillis = startedAtMillis

        fun retryAfterMillis(nowMillis: Long): Long {
            if (nowMillis < lastRefillMillis) {
                return lastRefillMillis - nowMillis
            }

            refill(nowMillis)
            if (tokens > 0) {
                return 0
            }

            return refillMillis - (nowMillis - lastRefillMillis)
        }

        fun consume() {
            check(tokens > 0)
            tokens -= 1
        }

        private fun refill(nowMillis: Long) {
            val elapsedMillis = nowMillis - lastRefillMillis
            val refillCount = elapsedMillis / refillMillis
            if (refillCount == 0L) {
                return
            }

            val addedTokens = minOf(refillCount, capacity.toLong()).toInt()
            tokens = minOf(capacity, tokens + addedTokens)
            lastRefillMillis += refillCount * refillMillis
        }
    }
}
