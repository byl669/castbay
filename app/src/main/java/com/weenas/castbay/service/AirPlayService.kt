package com.weenas.castbay.service

import com.weenas.castbay.util.Diagnostics
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.weenas.castbay.MainActivity
import com.weenas.castbay.util.Log

class AirPlayService : Service() {

    companion object {
        const val ACTION_START = "com.weenas.castbay.action.START_RECEIVER"
        const val ACTION_STOP = "com.weenas.castbay.action.STOP_RECEIVER"
        const val CHANNEL_ID = "castbay_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "CastBayService"

        /** Tried in turn; see [enterForeground]. */
        private val FOREGROUND_TYPES = listOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        /**
         * When the app last asked this service to stop (SystemClock.elapsedRealtime; 0 before). A start request
         * made soon after is lost on some boards (#239): see [com.weenas.castbay.viewmodel.AirPlayViewModel.startServer].
         */
        @Volatile var lastStopRequestedAt = 0L

        /** Whether the receiver is up, for [com.weenas.castbay.receiver.ReceiverStarter]. */
        @Volatile var isReceiving = false
            private set
    }

    private val manager by lazy { AirPlayManager.getInstance(this) }
    private var running = false
        set(value) {
            field = value
            isReceiving = value
        }
    private val stateCallback: (AirPlayConnectionState, StreamInfo, String?) -> Unit = { state, _, _ ->
        if (running) {
            when (state) {
                AirPlayConnectionState.AdvertisingOnly -> getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(getString(com.weenas.castbay.R.string.notification_discoverable_only)))
                AirPlayConnectionState.Error -> {
                    running = false
                    removeForeground()
                    stopSelf()
                }
                else -> Unit
            }
        }
    }

    // Its notification in the language chosen in Settings.
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
        Diagnostics.record("service", "Created")
        Diagnostics.receiverRunning(true)
        // Brings the receiver back once the device has a network again, e.g. a car waking up.
        com.weenas.castbay.receiver.WakeJobService.schedule(this)
        createNotificationChannel()
        manager.registerStateCallback(stateCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diagnostics.record("service", "Start command ${intent?.action ?: "(restart)"}" + if (running) ", already running" else "")
        if (intent?.action == ACTION_STOP) {
            if (running) manager.stop()
            running = false
            // With the id: a start request that came in after this stop is not thrown away with it
            // (stopSelf() without one stops the service whatever is queued, #239).
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        if (!enterForeground(buildNotification(getString(com.weenas.castbay.R.string.notification_starting)))) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val settings = ReceiverSettingsStore(this).load()
        // A finished day's usage statistics, if the person turned them on.
        kotlin.concurrent.thread(name = "CastBay-stats") { com.weenas.castbay.util.UsageStats.sendFinishedDays(applicationContext) }
        // Started at boot without the app being opened (a car): a crash from the last run is
        // sent here, if the person turned that on; the app asks otherwise, when opened.
        if (settings.sendErrorReports && com.weenas.castbay.util.CrashReports.pending(this) != null) {
            val context = applicationContext
            kotlin.concurrent.thread(name = "CastBay-crash-report") {
                com.weenas.castbay.util.CrashReports.send(context, com.weenas.castbay.util.AppVersion.name(context))
            }
        }
        if (!manager.start(settings)) {
            Diagnostics.record("service", "Receiver failed to start")
            removeForeground()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(
                getString(
                    if (manager.isDiscoveryOnly) com.weenas.castbay.R.string.notification_discovery_only
                    else com.weenas.castbay.R.string.notification_waiting
                )
            ))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Diagnostics.record("service", "Destroyed" + if (running) " while running" else "")
        Diagnostics.receiverRunning(false)
        if (running) manager.stop()
        manager.unregisterStateCallback(stateCallback)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(com.weenas.castbay.R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Into the foreground, as media playback and a connected-device service (the phones casting
     * here). Android 15 and later don't let a service started at boot (BOOT_COMPLETED) take on
     * media playback, so the receiver then waits as a connected-device service alone, which is
     * enough to keep it running. False if neither is allowed.
     */
    private fun enterForeground(notification: Notification): Boolean {
        for (type in FOREGROUND_TYPES) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
                if (type != FOREGROUND_TYPES.first()) Diagnostics.record("service", "In the foreground as a connected device only")
                return true
            } catch (error: RuntimeException) {
                // ForegroundServiceStartNotAllowedException (an IllegalStateException), or a SecurityException.
                Diagnostics.record("service", "Foreground type $type not allowed (${error.javaClass.simpleName}: ${error.message?.take(80)})")
                Log.w(TAG, "Foreground service type $type not allowed", error)
            }
        }
        return false
    }

    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(getString(com.weenas.castbay.R.string.app_name))
            .setContentText(message)
            .setSmallIcon(com.weenas.castbay.R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
