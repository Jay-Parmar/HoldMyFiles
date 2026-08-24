package dev.jay.holdmyfiles.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import dev.jay.holdmyfiles.R
import dev.jay.holdmyfiles.core.ServerStatus
import dev.jay.holdmyfiles.core.server.BoundEndpoint
import dev.jay.holdmyfiles.network.LocalIpv4Resolution
import dev.jay.holdmyfiles.network.LocalIpv4Resolver
import dev.jay.holdmyfiles.server.SharingRun
import dev.jay.holdmyfiles.server.SharingRunFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

interface SharingServiceDependencies {
    val sharingStateStore: SharingStateStore
    val sharingRunFactory: SharingRunFactory
    val localIpv4Resolver: LocalIpv4Resolver
}

class SharingService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val commands = Channel<ServiceCommand>(capacity = Channel.UNLIMITED)
    private var dependencies: SharingServiceDependencies? = null
    private var activeRun: SharingRun? = null

    override fun onCreate() {
        super.onCreate()
        dependencies = application as? SharingServiceDependencies
        serviceScope.launch {
            for (command in commands) {
                when (command) {
                    is ServiceCommand.Start -> startSharing(command.startId)
                    is ServiceCommand.Stop -> stopSharing(command.startId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SharingServiceActions.Start -> acceptStart(startId)
            SharingServiceActions.Stop -> commands.trySend(ServiceCommand.Stop(startId))
            else -> commands.trySend(ServiceCommand.Stop(startId))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        dependencies?.sharingStateStore?.beginStop()
        commands.trySend(ServiceCommand.Stop(startId))
    }

    override fun onDestroy() {
        commands.close()
        serviceJob.cancel()
        val run = activeRun
        activeRun = null
        if (run != null) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    run.stop()
                }
            }
        }

        dependencies?.sharingStateStore?.let { stateStore ->
            when (stateStore.status.value) {
                ServerStatus.Starting,
                is ServerStatus.Running,
                ServerStatus.Stopping,
                -> stateStore.publish(ServerStatus.Stopped)

                ServerStatus.Stopped,
                is ServerStatus.Failed,
                -> Unit
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun acceptStart(startId: Int) {
        val stateStore = dependencies?.sharingStateStore
        if (stateStore == null) {
            ensureNotificationChannel()
            startForegroundCompat(buildNotification(ServerStatus.Starting))
            commands.trySend(ServiceCommand.Stop(startId))
            return
        }

        val accepted = when (stateStore.status.value) {
            ServerStatus.Stopped,
            is ServerStatus.Failed,
            -> stateStore.beginStart()

            ServerStatus.Starting,
            is ServerStatus.Running,
            -> true

            ServerStatus.Stopping -> false
        }

        ensureNotificationChannel()
        startForegroundCompat(buildNotification(stateStore.status.value))
        if (!accepted) {
            commands.trySend(ServiceCommand.Stop(startId))
            return
        }

        commands.trySend(ServiceCommand.Start(startId))
    }

    private suspend fun startSharing(startId: Int) {
        if (activeRun != null) {
            return
        }

        val dependencies = dependencies
        if (dependencies == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return
        }

        dependencies.sharingStateStore.publish(ServerStatus.Starting)
        val address = when (val resolution = safeResolveAddress(dependencies.localIpv4Resolver)) {
            is LocalIpv4Resolution.Available -> resolution.address
            LocalIpv4Resolution.PermissionRequired -> {
                failStart(
                    stateStore = dependencies.sharingStateStore,
                    startId = startId,
                    message = LOCAL_NETWORK_PERMISSION_MESSAGE,
                )
                return
            }

            LocalIpv4Resolution.Unavailable -> {
                failStart(
                    stateStore = dependencies.sharingStateStore,
                    startId = startId,
                    message = NO_LOCAL_NETWORK_MESSAGE,
                )
                return
            }
        }

        var candidate: SharingRun? = null
        try {
            candidate = dependencies.sharingRunFactory.create(address)
            activeRun = candidate
            val endpoint = try {
                withTimeout(START_TIMEOUT_MILLIS) {
                    candidate.start()
                }
            } catch (_: TimeoutCancellationException) {
                throw StartTimedOutException()
            }
            dependencies.sharingStateStore.publish(
                ServerStatus.Running(
                    address = endpoint.host,
                    port = endpoint.port,
                    pin = candidate.pin,
                ),
            )
            notificationManager().notify(
                NOTIFICATION_ID,
                buildNotification(dependencies.sharingStateStore.status.value),
            )
        } catch (cause: Throwable) {
            runCatching { candidate?.stop() }
            if (activeRun === candidate) {
                activeRun = null
            }
            if (cause is CancellationException) {
                throw cause
            }
            failStart(
                stateStore = dependencies.sharingStateStore,
                startId = startId,
                message = START_FAILED_MESSAGE,
            )
        }
    }

    private suspend fun stopSharing(startId: Int) {
        val dependencies = dependencies
        val run = activeRun
        if (run == null) {
            dependencies?.sharingStateStore?.publish(ServerStatus.Stopped)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return
        }

        dependencies?.sharingStateStore?.publish(ServerStatus.Stopping)
        notificationManager().notify(
            NOTIFICATION_ID,
            buildNotification(ServerStatus.Stopping),
        )
        runCatching { run.stop() }
        activeRun = null
        dependencies?.sharingStateStore?.publish(ServerStatus.Stopped)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun safeResolveAddress(resolver: LocalIpv4Resolver): LocalIpv4Resolution =
        try {
            resolver.resolve()
        } catch (_: RuntimeException) {
            LocalIpv4Resolution.Unavailable
        }

    private fun failStart(
        stateStore: SharingStateStore,
        startId: Int,
        message: String,
    ) {
        stateStore.publish(ServerStatus.Failed(message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = NOTIFICATION_CHANNEL_DESCRIPTION
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: ServerStatus): Notification {
        val (title, text) = when (status) {
            ServerStatus.Stopped -> "File sharing stopped" to "Sharing is not active"
            ServerStatus.Starting -> "Starting file sharing" to "Preparing the local address"
            is ServerStatus.Running -> {
                "Sharing files" to "Open ${BoundEndpoint(status.address, status.port).origin}"
            }

            ServerStatus.Stopping -> "Stopping file sharing" to "Closing guest access"
            is ServerStatus.Failed -> "File sharing unavailable" to status.message
        }

        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(
                status == ServerStatus.Starting ||
                    status is ServerStatus.Running ||
                    status == ServerStatus.Stopping,
            )
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (status == ServerStatus.Starting || status is ServerStatus.Running) {
            val stopIntent = PendingIntent.getService(
                this,
                STOP_ACTION_REQUEST_CODE,
                SharingServiceActions.intent(this, SharingServiceActions.Stop),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_share),
                    "Stop",
                    stopIntent,
                ).build(),
            )
        }

        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    OPEN_APP_REQUEST_CODE,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private sealed interface ServiceCommand {
        val startId: Int

        data class Start(override val startId: Int) : ServiceCommand

        data class Stop(override val startId: Int) : ServiceCommand
    }

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "file_sharing"
        const val NOTIFICATION_CHANNEL_NAME = "File sharing"
        const val NOTIFICATION_CHANNEL_DESCRIPTION = "Shows when local file sharing is active"
        const val NOTIFICATION_ID = 1_001
        const val STOP_ACTION_REQUEST_CODE = 1
        const val OPEN_APP_REQUEST_CODE = 2
        const val START_TIMEOUT_MILLIS = 15_000L
        const val LOCAL_NETWORK_PERMISSION_MESSAGE =
            "Allow local network access, then try again."
        const val NO_LOCAL_NETWORK_MESSAGE =
            "Connect to Wi-Fi or turn on your hotspot, then try again."
        const val START_FAILED_MESSAGE = "Sharing could not start. Try again."
    }

    private class StartTimedOutException : RuntimeException()
}
