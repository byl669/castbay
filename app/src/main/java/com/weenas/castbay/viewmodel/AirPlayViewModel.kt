package com.weenas.castbay.viewmodel

import android.app.Application
import android.content.Intent
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.weenas.castbay.service.AirPlayConnectionState
import com.weenas.castbay.service.AirPlayManager
import com.weenas.castbay.service.StreamInfo
import com.weenas.castbay.service.UpdateInstall
import com.weenas.castbay.service.UpdateInstaller
import com.weenas.castbay.BuildConfig
import com.weenas.castbay.util.AppVersion
import com.weenas.castbay.util.CrashReports
import com.weenas.castbay.util.Diagnostics
import com.weenas.castbay.util.Log
import com.weenas.castbay.util.UsageStats
import com.weenas.castbay.service.ReceiverSettings
import com.weenas.castbay.service.ReceiverSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AirPlayUiState(
    val connectionState: AirPlayConnectionState = AirPlayConnectionState.Idle,
    val streamInfo: StreamInfo = StreamInfo(),
    val errorMessage: String? = null
)

class AirPlayViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = AirPlayManager.getInstance(application)
    private val settingsStore = ReceiverSettingsStore(application)

    private val _state = MutableStateFlow(AirPlayUiState())
    val state: StateFlow<AirPlayUiState> = _state.asStateFlow()
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<ReceiverSettings> = _settings.asStateFlow()

    private val networkMonitor = com.weenas.castbay.service.NetworkMonitor(application).also { it.start() }
    /** The TV's network (type, Wi-Fi name, IP) for the home screen. */
    val network: StateFlow<com.weenas.castbay.service.NetworkStatus> = networkMonitor.status
    /** The sender's volume, as it changes: the TV shows it briefly (VolumeIndicator). */
    val senderVolume get() = manager.senderVolume
    /** A PIN a new sender must enter (PIN pairing), shown over any screen; null otherwise. */
    val pairingPin get() = manager.pairingPin

    /** Puts the PIN away; the device gets a new one when it tries again. */
    fun dismissPairingPin() = manager.hidePairingPin()

    /** A new device asking to cast, while new devices need approval. */
    val deviceRequest get() = manager.deviceRequest
    fun answerDeviceRequest(allow: Boolean) = manager.answerDeviceRequest(allow)
    fun dismissDeviceRequest() = manager.dismissDeviceRequest()

    /** Devices that have cast here, each allowed or blocked. */
    fun knownDevices() = manager.knownDevices()
    fun setDeviceAllowed(deviceId: String, allowed: Boolean) = manager.setDeviceAllowed(deviceId, allowed)
    /** Forgets every device, allowed, blocked or paired by PIN: they are new again. */
    fun forgetDevices() = manager.forgetDevices()

    val appVersion: String = com.weenas.castbay.util.AppVersion.name(application)

    fun canReadWifiName() = networkMonitor.canReadSsid()

    /** How mirroring is offered with [settings] on this TV, e.g. "H.265, up to 4K". */
    fun mirroringProfile(settings: ReceiverSettings) = manager.mirroringProfile(settings)

    /** Call after the location permission was granted, so the Wi-Fi name appears. */
    fun refreshNetwork() = networkMonitor.refresh()

    // StateFlow, not LiveData: Compose only recomposes for state it observes.
    /** A screen the home screen asked to open ("settings", "help" or "about"), until it is shown. */
    private val _navigateTo = MutableStateFlow<String?>(null)
    val navigateTo: StateFlow<String?> = _navigateTo.asStateFlow()

    private val _navigateBack = MutableStateFlow(false)
    val navigateBack: StateFlow<Boolean> = _navigateBack.asStateFlow()

    private val stateCallback: (AirPlayConnectionState, StreamInfo, String?) -> Unit = { state, streamInfo, error ->
        _state.value = AirPlayUiState(state, streamInfo, error)
    }

    private val startHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** The person stopped the receiver (Back twice, a Stop button): it is not started again behind their back. */
    private var stoppedByPerson = false
    private var startChecks = 0

    /**
     * The receiver must come up when the app opens. On an Allwinner board (#239) the request to start the
     * service never reached it after the app was quit and opened again in the same process, and the screen
     * said "Starting…" for ever: after 5 s still idle, ask again, twice, and then start the receiver here
     * without the service (AirPlay works while the app is open).
     */
    private val startCheck: Runnable = Runnable {
        if (stoppedByPerson || _state.value.connectionState != com.weenas.castbay.service.AirPlayConnectionState.Idle) return@Runnable
        startChecks++
        if (startChecks <= START_CHECK_SERVICE_TRIES) {
            Diagnostics.record("app", "The receiver is still off after ${START_CHECK_MS / 1000} s: asking the service again ($startChecks)")
            askServiceToStart()
        } else {
            Diagnostics.record("app", "The receiver is still off: starting it without the service")
            manager.start()
        }
        if (startChecks < START_CHECK_MAX) startHandler.postDelayed(startCheck, START_CHECK_MS)
    }

    /** After a quick reopen: the service is asked once the spell in which its start requests are lost is over. */
    private val askServiceLater: Runnable = Runnable {
        if (!stoppedByPerson && !com.weenas.castbay.service.AirPlayService.isReceiving) askServiceToStart()
    }

    init {
        manager.registerStateCallback(stateCallback)
        // Opening the app starts the receiver (a no-op if it is running), like turning on an Apple TV.
        startServer()
    }

    fun startServer() {
        stoppedByPerson = false
        startChecks = 0
        startHandler.removeCallbacks(startCheck)
        startHandler.removeCallbacks(askServiceLater)
        val sinceStop = android.os.SystemClock.elapsedRealtime() - com.weenas.castbay.service.AirPlayService.lastStopRequestedAt
        if (com.weenas.castbay.service.AirPlayService.lastStopRequestedAt != 0L && sinceStop < QUICK_REOPEN_MS) {
            // Opened again soon after quitting: on the Allwinner board of #239 the system lost every start
            // request for the service in the first seconds (up to 15 s) after it stopped. Not asking it yet:
            // the receiver starts here at once, and the service, which only holds it in the foreground, is
            // asked once that spell is over.
            Diagnostics.record("app", "Opened ${sinceStop} ms after quitting: starting the receiver here, the service follows")
            manager.start()
            startHandler.postDelayed(askServiceLater, QUICK_REOPEN_MS - sinceStop + 1_000)
        } else {
            askServiceToStart()
        }
        startHandler.postDelayed(startCheck, START_CHECK_MS)
    }

    private fun askServiceToStart() {
        val app = getApplication<Application>()
        if (com.weenas.castbay.BuildConfig.DEBUG &&
            app.getSharedPreferences("airplay_identity", android.content.Context.MODE_PRIVATE).getBoolean("drop_service_start", false)
        ) {
            // For trying the recovery below on a healthy device (debug builds only).
            Diagnostics.record("app", "Asking the service to start the receiver: dropped for testing")
            return
        }
        Diagnostics.record("app", "Asking the service to start the receiver")
        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, com.weenas.castbay.service.AirPlayService::class.java)
                    .setAction(com.weenas.castbay.service.AirPlayService.ACTION_START)
            )
        } catch (error: Exception) {
            Diagnostics.record("app", "The service could not be asked: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    fun stopServer() {
        stoppedByPerson = true
        startHandler.removeCallbacks(startCheck)
        startHandler.removeCallbacks(askServiceLater)
        com.weenas.castbay.service.AirPlayService.lastStopRequestedAt = android.os.SystemClock.elapsedRealtime()
        getApplication<Application>().startService(
            Intent(getApplication(), com.weenas.castbay.service.AirPlayService::class.java)
                .setAction(com.weenas.castbay.service.AirPlayService.ACTION_STOP)
        )
        manager.stop()
    }

    fun remoteControl(command: com.weenas.castbay.service.DacpClient.Command) = manager.remoteControl(command)

    fun skipMusic(forward: Boolean) = manager.skipMusic(forward)

    fun seekMusic(positionSec: Double) = manager.seekMusic(positionSec)

    fun toggleVideoPause() = manager.toggleVideoPause()

    private val lyricsClient = com.weenas.castbay.service.LyricsClient(appVersion)

    private val updateChecker = com.weenas.castbay.service.UpdateChecker(application, appVersion) { _settings.value.testVersions }
    /** Whether CastBay looks for updates itself: not in store builds (F-Droid), which the store updates. */
    private val checksUpdates get() = BuildConfig.SELF_UPDATE && _settings.value.checkUpdates
    private val _update = MutableStateFlow(if (checksUpdates) updateChecker.known() else null)
    /** A newer CastBay on GitHub, found by the daily check (off in Settings, or a store build: none). */
    val update: StateFlow<com.weenas.castbay.service.AppUpdate?> = _update.asStateFlow()

    private fun checkForUpdate() {
        if (!checksUpdates) {
            _update.value = null
            return
        }
        kotlin.concurrent.thread(name = "CastBay-update") {
            val found = updateChecker.check()
            if (checksUpdates) _update.value = found
        }
    }

    private val updateInstaller = UpdateInstaller(application)
    private val _updateInstall = MutableStateFlow<UpdateInstall>(UpdateInstall.Idle)
    /** Where downloading and installing [update] has got to (About's Update button). */
    val updateInstall: StateFlow<UpdateInstall> = _updateInstall.asStateFlow()

    /** How the last Check for updates on About went: null before one. */
    enum class UpdateCheck { CHECKING, UP_TO_DATE, FAILED }
    private val _updateCheck = MutableStateFlow<UpdateCheck?>(null)
    val updateCheck: StateFlow<UpdateCheck?> = _updateCheck.asStateFlow()

    /**
     * Checks for a newer version now (About's button). Pressing it is asking to check, so it
     * works with the daily check turned off too.
     */
    fun checkForUpdateNow() {
        if (_updateCheck.value == UpdateCheck.CHECKING) return
        _updateCheck.value = UpdateCheck.CHECKING
        kotlin.concurrent.thread(name = "CastBay-update-check") {
            updateChecker.checkNow()
                .onSuccess { found ->
                    _update.value = found
                    _updateCheck.value = if (found == null) UpdateCheck.UP_TO_DATE else null
                }
                .onFailure { _updateCheck.value = UpdateCheck.FAILED }
        }
    }

    /** Downloads [update] and opens Android's installer on it; the person confirms there. */
    fun installUpdate() {
        val target = _update.value ?: return
        if (!BuildConfig.SELF_UPDATE) return
        if (_updateInstall.value is UpdateInstall.Downloading) return
        _updateInstall.value = UpdateInstall.Downloading(null)
        kotlin.concurrent.thread(name = "CastBay-update-download") {
            val file = try {
                updateInstaller.download(target) { _updateInstall.value = UpdateInstall.Downloading(it) }
            } catch (error: UpdateInstaller.ChecksumException) {
                _updateInstall.value = UpdateInstall.Failed(UpdateInstall.Reason.CHECKSUM)
                return@thread
            } catch (error: Exception) {
                _updateInstall.value = UpdateInstall.Failed(UpdateInstall.Reason.DOWNLOAD)
                return@thread
            }
            _updateInstall.value = try {
                updateInstaller.install(file)
                UpdateInstall.Installing
            } catch (error: Exception) {
                Log.w("CastBayUpdate", "No installer for the update", error)
                UpdateInstall.Failed(UpdateInstall.Reason.INSTALLER)
            }
        }
    }

    // After the checker above: initialisers and init blocks run in the order written.
    init {
        checkForUpdate()
    }

    private val _lastActivity = MutableStateFlow(android.os.SystemClock.elapsedRealtime())
    /** When the person last pressed a key or touched the screen (elapsedRealtime), for the screen saver. */
    val lastActivity: StateFlow<Long> = _lastActivity.asStateFlow()

    /**
     * Whether the music screen saver is dimming or blacking out the screen: the next key or
     * touch then only wakes it (MainActivity), so it can't pause or close anything by accident.
     */
    @Volatile var screenSaverOn = false

    fun noteActivity() {
        _lastActivity.value = android.os.SystemClock.elapsedRealtime()
    }

    /** The home screen's note about a crash in the last run: asking, sending, sent or failed. */
    sealed interface CrashNotice {
        data object Ask : CrashNotice
        data object Sending : CrashNotice
        data class Sent(val id: String) : CrashNotice
        data object Failed : CrashNotice
    }
    private val _crashNotice = MutableStateFlow<CrashNotice?>(null)
    val crashNotice: StateFlow<CrashNotice?> = _crashNotice.asStateFlow()

    init {
        // A crash from the last run: sent quietly if the person turned that on, else asked about.
        if (CrashReports.pending(getApplication()) != null) {
            if (_settings.value.sendErrorReports) sendCrashReport(quiet = true) else _crashNotice.value = CrashNotice.Ask
        }
    }

    /** Sends the last run's crash as a problem report; [quiet] shows nothing (Settings' switch). */
    fun sendCrashReport(quiet: Boolean = false) {
        val app = getApplication<Application>()
        if (!quiet) _crashNotice.value = CrashNotice.Sending
        kotlin.concurrent.thread(name = "CastBay-crash-report") {
            val result = CrashReports.send(app, AppVersion.name(app)) ?: return@thread
            if (!quiet) _crashNotice.value = result.fold({ CrashNotice.Sent(it) }, { CrashNotice.Failed })
        }
    }

    /**
     * Hides the note: asked, that forgets the crash; after a failed send, it is kept and
     * offered again at the next start.
     */
    fun dismissCrashNotice() {
        if (_crashNotice.value == CrashNotice.Ask) CrashReports.clear(getApplication())
        _crashNotice.value = null
    }

    private val _usageStatsOn = MutableStateFlow(UsageStats.isOn(application))
    /** Whether anonymous usage statistics are on (Settings → General; off by default). */
    val usageStatsOn: StateFlow<Boolean> = _usageStatsOn.asStateFlow()

    fun setUsageStats(on: Boolean) {
        UsageStats.setOn(getApplication(), on)
        _usageStatsOn.value = on
    }

    fun setSendErrorReports(enabled: Boolean) {
        updateSettings { it.copy(sendErrorReports = enabled) }
        // Turning it on while asked about a crash: that is a yes.
        if (enabled && _crashNotice.value == CrashNotice.Ask) sendCrashReport()
    }

    /** Synced lyrics for a song, or null; looked up online, so call off the main thread. */
    fun findLyrics(title: String, artist: String?, album: String?, durationSec: Double) =
        lyricsClient.find(title, artist, album, durationSec)

    /** Stops the current cast from the TV; the receiver keeps waiting for the next one. */
    fun endCasting() = manager.endCasting()

    /** For the stats overlay; main thread. */
    fun playbackStats() = manager.playbackStats()

    fun seekVideoBy(deltaSec: Int) = manager.seekVideoBy(deltaSec)

    /** The AirPlay video player, if one is active. Main thread only. */
    val videoPlayer get() = manager.videoPlayer

    fun setVideoSurface(surface: Surface?) {
        manager.setVideoSurface(surface)
    }

    /** Turns the daily update check on or off; on checks now (if it hasn't today). */
    fun setCheckUpdates(enabled: Boolean) {
        updateSettings { it.copy(checkUpdates = enabled) }
        checkForUpdate()
    }

    /** Test versions on or off for the update check; looks again at once, the list changes with it. */
    fun setTestVersions(enabled: Boolean) {
        updateSettings { it.copy(testVersions = enabled) }
        if (!checksUpdates) return
        kotlin.concurrent.thread(name = "CastBay-update") {
            val found = updateChecker.check(force = true)
            if (checksUpdates) _update.value = found
        }
    }

    /** Every setting back to its default (the TV's own name is kept: it isn't a setting). */
    fun resetSettings() = updateSettings { ReceiverSettings(tvName = it.tvName) }

    fun updateSettings(transform: (ReceiverSettings) -> ReceiverSettings) {
        val previous = _settings.value
        val updated = transform(previous)
        if (updated == previous) return
        _settings.value = updated
        settingsStore.save(updated)
        // Only protocol settings need the receiver restarted (not start-on-boot or the overlay).
        if (updated.needsRestartComparedTo(previous)) manager.restartIfRunning(updated)
    }

    /** The home screen button that opened the current screen, focused again on the way back. */
    var homeFocus: String = "settings"
        private set

    fun navigateToSettings() = navigateTo("settings")

    fun navigateToAbout() = navigateTo("about")

    fun navigateToHelp() = navigateTo("help")

    /** About → the new versions' release notes; Back returns to About. */
    fun navigateToChangelog() {
        _navigateTo.value = "changelog"
    }

    /** About → the open-source licences; Back returns to About. */
    fun navigateToLicenses() {
        _navigateTo.value = "licenses"
    }

    /** About → Diagnostics; Back returns to About. */
    fun navigateToDiagnostics() {
        _navigateTo.value = "diagnostics"
    }

    private fun navigateTo(screen: String) {
        homeFocus = screen
        _navigateTo.value = screen
    }

    fun navigateBack() {
        _navigateBack.value = true
    }

    fun onNavigateToConsumed() {
        _navigateTo.value = null
    }

    fun onNavigateBackConsumed() {
        _navigateBack.value = false
    }

    override fun onCleared() {
        super.onCleared()
        startHandler.removeCallbacks(startCheck)
        startHandler.removeCallbacks(askServiceLater)
        networkMonitor.stop()
        manager.unregisterStateCallback(stateCallback)
    }

    private companion object {
        /** Opened again within this long of quitting counts as soon (the start requests were lost for up to 15 s). */
        const val QUICK_REOPEN_MS = 20_000L
        const val START_CHECK_MS = 5_000L
        const val START_CHECK_SERVICE_TRIES = 2
        const val START_CHECK_MAX = 3
    }
}
