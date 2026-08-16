package dev.jay.holdmyfiles.core

import dev.jay.holdmyfiles.core.security.RunPin

sealed interface ServerStatus {
    data object Stopped : ServerStatus

    data object Starting : ServerStatus

    data class Running(
        val address: String,
        val port: Int,
        val pin: RunPin,
    ) : ServerStatus

    data object Stopping : ServerStatus

    data class Failed(val message: String) : ServerStatus
}

data class HomeUiState(
    val enabledShareCount: Int = 0,
    val serverStatus: ServerStatus = ServerStatus.Stopped,
) {
    init {
        require(enabledShareCount >= 0)
    }

    val canStart: Boolean
        get() = enabledShareCount > 0 && serverStatus == ServerStatus.Stopped

    val canStop: Boolean
        get() = serverStatus == ServerStatus.Starting || serverStatus is ServerStatus.Running
}
