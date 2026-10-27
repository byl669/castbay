package com.weenas.castbay

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import com.weenas.castbay.util.Diagnostics
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weenas.castbay.service.AppLanguage
import com.weenas.castbay.ui.LocalBackgroundImage
import com.weenas.castbay.ui.DeviceRequestDialog
import com.weenas.castbay.ui.PairingPinDialog
import com.weenas.castbay.ui.screen.MirrorScreen
import com.weenas.castbay.ui.screen.SettingsScreen
import com.weenas.castbay.ui.screen.AboutScreen
import com.weenas.castbay.ui.screen.ChangelogScreen
import com.weenas.castbay.ui.screen.LicensesScreen
import com.weenas.castbay.ui.screen.DiagnosticsScreen
import com.weenas.castbay.ui.screen.HelpScreen
import com.weenas.castbay.ui.theme.CastBayTheme
import com.weenas.castbay.viewmodel.AirPlayViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AirPlayViewModel by viewModels()
    // A key or touch that woke the screen saver: the rest of it (key up, the touch's moves and
    // lift) goes nowhere either.
    private var wakingKey = -1
    private var wakingTouch = false

    // The language chosen in Settings (Settings recreates the activity when it changes).
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    /** Every key as it arrives (Diagnostics): which keys a remote or a car's buttons send. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Diagnostics.record("key", KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_") +
                " (${event.keyCode}) from ${event.device?.name ?: "?"}")
        }
        // Media keys (a steering wheel's too) always work and don't wake the screen saver;
        // any other key wakes it, and does nothing else.
        if (event.keyCode !in MEDIA_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val asleep = viewModel.screenSaverOn
                viewModel.noteActivity()
                if (asleep) {
                    wakingKey = event.keyCode
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP && event.keyCode == wakingKey) {
                wakingKey = -1
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A touch wakes the screen saver (and does nothing else); any touch counts as activity. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val asleep = viewModel.screenSaverOn
            viewModel.noteActivity()
            wakingTouch = asleep
        }
        if (wakingTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) wakingTouch = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme only paints the launch screen; the app itself has a plain background.
        setTheme(R.style.Theme_CastBay)
        super.onCreate(savedInstanceState)
        Diagnostics.init(this)
        Diagnostics.record("app", "Opened")
        setContent {
            // Decoded once for the home and Settings backgrounds (the same artwork as the launch screen).
            val backgroundImage = ImageBitmap.imageResource(R.drawable.splash_image)
            CompositionLocalProvider(LocalBackgroundImage provides backgroundImage) {
                CastBayTheme { Screens() }
            }
        }
    }
}

@Composable
private fun Screens() {
    // Saved, so recreating the activity for a new language stays in Settings.
    var currentScreen by rememberSaveable { mutableStateOf("mirror") }
    val viewModel: AirPlayViewModel = viewModel()

    val navigateTo by viewModel.navigateTo.collectAsState()
    val navigateBack by viewModel.navigateBack.collectAsState()
    // Diagnostics is opened from About, or from the error screen; Back returns to where it came from.
    var diagnosticsBack by rememberSaveable { mutableStateOf("about") }

    LaunchedEffect(navigateTo) {
        navigateTo?.let {
            if (it == "diagnostics") diagnosticsBack = if (currentScreen == "mirror") "mirror" else "about"
            currentScreen = it
            viewModel.onNavigateToConsumed()
        }
    }

    LaunchedEffect(navigateBack) {
        if (navigateBack) {
            currentScreen = "mirror"
            viewModel.onNavigateBackConsumed()
        }
    }

    // A cast that starts while Settings or About is open takes the screen, as on an Apple TV.
    // Only on the transition: opening Settings during a cast still works.
    val state by viewModel.state.collectAsState()
    val streaming = state.connectionState == com.weenas.castbay.service.AirPlayConnectionState.Streaming
    LaunchedEffect(streaming) {
        if (streaming) currentScreen = "mirror"
    }

    // The remote's Back key leaves Settings, Help and About for the home screen instead of closing the app.
    BackHandler(enabled = currentScreen != "mirror") {
        // Diagnostics and the release notes are opened from About, and go back there.
        currentScreen = when (currentScreen) {
            "diagnostics" -> diagnosticsBack
            "changelog", "licenses" -> "about"
            else -> "mirror"
        }
    }

    Box {
        when (currentScreen) {
            "mirror" -> MirrorScreen(viewModel = viewModel)
            "settings" -> SettingsScreen(viewModel = viewModel)
            "help" -> HelpScreen(viewModel = viewModel)
            "about" -> AboutScreen(viewModel = viewModel)
            "diagnostics" -> DiagnosticsScreen()
            "changelog" -> ChangelogScreen(viewModel = viewModel)
            "licenses" -> LicensesScreen()
        }
        val pairingPin by viewModel.pairingPin.collectAsState()
        PairingPinDialog(pairingPin, onDismiss = viewModel::dismissPairingPin)
        val deviceRequest by viewModel.deviceRequest.collectAsState()
        DeviceRequestDialog(deviceRequest, onAnswer = viewModel::answerDeviceRequest, onDismiss = viewModel::dismissDeviceRequest)
    }
}

/**
 * The keys a media session handles (as KeyEvent.isMediaSessionKey, which is Android 12+: before
 * that, calling it crashed on any key).
 */
private val MEDIA_KEYS = setOf(
    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_STOP,
    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
    KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
)
