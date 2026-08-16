package dev.jay.holdmyfiles.core.security

fun interface MonotonicClock {
    fun nowMillis(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
