package dev.jay.holdmyfiles.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAuthenticatorTest {
    @Test
    fun `valid pin issues an authenticated session`() {
        val authenticator = authenticator()

        val result = authenticator.login("192.168.1.2", "000042")

        val session = (result as LoginResult.Authenticated).session
        assertTrue(authenticator.validate(session.encodedValue()))
    }

    @Test
    fun `invalid pin does not issue a session`() {
        val authenticator = authenticator()

        val result = authenticator.login("192.168.1.2", "000043")

        assertTrue(result is LoginResult.InvalidCredentials)
    }

    @Test
    fun `rate limit applies before pin verification`() {
        val clock = MutableClock()
        val authenticator = authenticator(
            limiter = PinRateLimiter(
                clock = clock,
                peerCapacity = 1,
                peerRefillMillis = 10,
                globalCapacity = 10,
                globalRefillMillis = 10,
                maxTrackedPeers = 10,
            ),
        )
        authenticator.login("192.168.1.2", "wrong")

        val result = authenticator.login("192.168.1.2", "000042")

        assertTrue(result is LoginResult.RateLimited)
    }

    @Test
    fun `clear invalidates sessions`() {
        val authenticator = authenticator()
        val result = authenticator.login("192.168.1.2", "000042")
        val session = (result as LoginResult.Authenticated).session

        authenticator.clear()

        assertFalse(authenticator.validate(session.encodedValue()))
    }

    private fun authenticator(
        limiter: PinRateLimiter = PinRateLimiter(),
    ): SessionAuthenticator {
        var seed = 0
        return SessionAuthenticator(
            pin = RunPinGenerator(RandomNumberSource { 42 }).generate(),
            sessions = SessionRegistry(
                random = RandomByteSource { destination -> destination.fill(seed++.toByte()) },
            ),
            limiter = limiter,
        )
    }

    private class MutableClock(
        var nowMillis: Long = 0,
    ) : MonotonicClock {
        override fun nowMillis(): Long = nowMillis
    }
}
