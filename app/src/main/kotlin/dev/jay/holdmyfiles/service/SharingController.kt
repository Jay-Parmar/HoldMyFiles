package dev.jay.holdmyfiles.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.jay.holdmyfiles.core.ServerStatus
import kotlinx.coroutines.flow.StateFlow

interface SharingController {
    val status: StateFlow<ServerStatus>

    fun start()

    fun stop()
}

class AndroidSharingController(
    context: Context,
    private val stateStore: SharingStateStore,
) : SharingController {
    private val applicationContext = context.applicationContext

    override val status: StateFlow<ServerStatus> = stateStore.status

    override fun start() {
        if (!stateStore.beginStart()) {
            return
        }

        try {
            ContextCompat.startForegroundService(
                applicationContext,
                SharingServiceActions.intent(applicationContext, SharingServiceActions.Start),
            )
        } catch (_: RuntimeException) {
            stateStore.replaceIfCurrent(
                expected = ServerStatus.Starting,
                replacement = ServerStatus.Failed(START_REQUEST_FAILED_MESSAGE),
            )
        }
    }

    override fun stop() {
        val previousStatus = stateStore.beginStop() ?: return
        try {
            applicationContext.startService(
                SharingServiceActions.intent(applicationContext, SharingServiceActions.Stop),
            )
        } catch (_: RuntimeException) {
            stateStore.replaceIfCurrent(
                expected = ServerStatus.Stopping,
                replacement = previousStatus,
            )
        }
    }

    private companion object {
        const val START_REQUEST_FAILED_MESSAGE = "Sharing could not start. Try again."
    }
}

internal object SharingServiceActions {
    const val Start = "dev.jay.holdmyfiles.action.START_SHARING"
    const val Stop = "dev.jay.holdmyfiles.action.STOP_SHARING"

    fun intent(context: Context, action: String): Intent =
        Intent(context, SharingService::class.java).setAction(action)
}
