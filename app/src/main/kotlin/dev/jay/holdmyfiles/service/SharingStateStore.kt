package dev.jay.holdmyfiles.service

import dev.jay.holdmyfiles.core.ServerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharingStateStore(
    initialStatus: ServerStatus = ServerStatus.Stopped,
) {
    private val mutableStatus = MutableStateFlow(initialStatus)

    val status: StateFlow<ServerStatus> = mutableStatus.asStateFlow()

    internal fun beginStart(): Boolean {
        while (true) {
            val current = mutableStatus.value
            if (current != ServerStatus.Stopped && current !is ServerStatus.Failed) {
                return false
            }
            if (mutableStatus.compareAndSet(current, ServerStatus.Starting)) {
                return true
            }
        }
    }

    internal fun beginStop(): ServerStatus? {
        while (true) {
            val current = mutableStatus.value
            if (current != ServerStatus.Starting && current !is ServerStatus.Running) {
                return null
            }
            if (mutableStatus.compareAndSet(current, ServerStatus.Stopping)) {
                return current
            }
        }
    }

    internal fun publish(status: ServerStatus) {
        mutableStatus.value = status
    }

    internal fun replaceIfCurrent(
        expected: ServerStatus,
        replacement: ServerStatus,
    ) {
        mutableStatus.compareAndSet(expected, replacement)
    }
}
