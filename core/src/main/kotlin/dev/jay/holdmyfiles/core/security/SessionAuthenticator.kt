package dev.jay.holdmyfiles.core.security

sealed interface LoginResult {
    data class Authenticated(
        val session: SessionToken,
    ) : LoginResult

    data object InvalidCredentials : LoginResult

    data class RateLimited(
        val retryAfterMillis: Long,
    ) : LoginResult

    data object CapacityReached : LoginResult
}

class SessionAuthenticator(
    private val pin: RunPin,
    private val sessions: SessionRegistry = SessionRegistry(),
    private val limiter: PinRateLimiter = PinRateLimiter(),
) {
    fun login(peerAddress: String, candidatePin: String): LoginResult {
        when (val decision = limiter.tryAcquire(peerAddress)) {
            RateLimitDecision.Allowed -> Unit
            is RateLimitDecision.Rejected -> {
                return LoginResult.RateLimited(decision.retryAfterMillis)
            }
        }

        if (!pin.verify(candidatePin)) {
            return LoginResult.InvalidCredentials
        }

        val session = sessions.issue() ?: return LoginResult.CapacityReached
        return LoginResult.Authenticated(session)
    }

    fun validate(encodedToken: String): Boolean = sessions.validate(encodedToken)

    fun logout(encodedToken: String) {
        sessions.revoke(encodedToken)
    }

    fun clear() {
        sessions.clear()
        limiter.clear()
    }
}
