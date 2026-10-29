package com.weenas.castbay.ui.screen

import com.weenas.castbay.ui.usingKeys
import com.weenas.castbay.ui.hasTouchScreen
import com.weenas.castbay.ui.isNarrowScreen
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.weenas.castbay.service.ReceiverSettings
import com.weenas.castbay.service.ScreenLauncher
import androidx.compose.ui.res.stringResource
import com.weenas.castbay.BuildConfig
import com.weenas.castbay.R
import com.weenas.castbay.ui.settingValueLabel
import com.weenas.castbay.ui.AppBackground
import com.weenas.castbay.ui.SegmentedChoice
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import com.weenas.castbay.viewmodel.AirPlayViewModel
import kotlinx.coroutines.delay

/** Settings' pages, one tab each. */
private enum class SettingsTab { CONNECTION, MIRRORING, PLAYBACK, GENERAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AirPlayViewModel) {
    val settings by viewModel.settings.collectAsState()
    // Kept across the recreation a language change causes, so the General page stays open.
    var tab by rememberSaveable { mutableStateOf(SettingsTab.CONNECTION) }
    // Starts on the tabs: no page scrolls, and no text field opens the keyboard. After a
    // language change recreates the screen, back on the language choice instead.
    val tabsFocus = remember { FocusRequester() }
    val languageFocus = remember { FocusRequester() }
    var refocusLanguage by rememberSaveable { mutableStateOf(false) }
    val keys = usingKeys()
    LaunchedEffect(Unit) {
        // On a touch screen nothing is focused up front (see usingKeys).
        if (keys || refocusLanguage) runCatching { (if (refocusLanguage) languageFocus else tabsFocus).requestFocus() }
        refocusLanguage = false
    }
    val activity = LocalContext.current as? android.app.Activity
    val recreateForLanguage = {
        refocusLanguage = true
        // The app's resources are chosen as the activity starts (MainActivity.attachBaseContext).
        activity?.recreate()
        Unit
    }
    val narrow = isNarrowScreen()
    AppBackground {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 48.dp, vertical = 24.dp)) {
            // The title and the tabs (one focus stop: Left and Right switch pages, Down enters
            // one). No Back button: the remote's Back key leaves, as on other TV apps.
            val title = @Composable {
                Text(stringResource(R.string.settings), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            val tabs = @Composable {
                SegmentedChoice(
                    tab,
                    SettingsTab.entries,
                    display = { stringResource(it.title) },
                    modifier = Modifier.focusRequester(tabsFocus)
                ) { tab = it }
            }
            if (narrow) {
                // On a phone held upright: the tabs below the title, scrolling sideways.
                title()
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { tabs() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    title()
                    Spacer(modifier = Modifier.width(32.dp))
                    tabs()
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            // A page fits a 540 dp tall screen; it scrolls in case (e.g. the password field).
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                val touch = hasTouchScreen()
                tab.note?.let { if (touch && it == R.string.section_playback_note) R.string.section_playback_note_touch else it }?.let {
                    Text(stringResource(it), color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                }
                SettingsCard {
                    when (tab) {
                        SettingsTab.CONNECTION -> ConnectionPage(settings, viewModel)
                        SettingsTab.MIRRORING -> MirroringPage(settings, viewModel)
                        SettingsTab.PLAYBACK -> PlaybackPage(settings, viewModel)
                        SettingsTab.GENERAL -> GeneralPage(settings, viewModel, languageFocus, recreateForLanguage)
                    }
                }
            }
        }
    }
}

private val SettingsTab.title
    get() = when (this) {
        SettingsTab.CONNECTION -> R.string.section_connection
        SettingsTab.MIRRORING -> R.string.section_mirroring
        SettingsTab.PLAYBACK -> R.string.section_playback
        SettingsTab.GENERAL -> R.string.section_general
    }

/** What a page's settings affect, above its card. */
private val SettingsTab.note: Int?
    get() = when (this) {
        // They change what senders see, so the receiver restarts for them.
        SettingsTab.CONNECTION -> R.string.section_connection_note
        // Screen mirroring only: apps' AirPlay and DLNA video and music aren't affected.
        SettingsTab.MIRRORING -> R.string.section_mirroring_note
        // Live, and also in the quick menu during playback.
        SettingsTab.PLAYBACK -> R.string.section_playback_note
        SettingsTab.GENERAL -> null
    }

@Composable
private fun ColumnScope.ConnectionPage(settings: ReceiverSettings, viewModel: AirPlayViewModel) {
    DeviceNameSetting(value = settings.deviceName) { name ->
        viewModel.updateSettings { it.copy(deviceName = name) }
    }
    SwitchSetting(stringResource(R.string.setting_append_tv_name), settings.appendTvName) { enabled ->
        viewModel.updateSettings { it.copy(appendTvName = enabled) }
    }
    Text(stringResource(R.string.setting_append_tv_name_note, settings.advertisedName), color = Color.Gray, fontSize = 14.sp)
    CardDivider()
    SwitchSetting(stringResource(R.string.setting_desktop), settings.desktopEnabled) { enabled ->
        viewModel.updateSettings { it.copy(desktopEnabled = enabled) }
    }
    SwitchSetting(stringResource(R.string.setting_dlna), settings.dlnaEnabled) { enabled ->
        viewModel.updateSettings { it.copy(dlnaEnabled = enabled) }
    }
    CardDivider()
    AccessSetting(settings, viewModel)
    CardDivider()
    ChoiceSetting(
        stringResource(R.string.setting_takeover),
        if (settings.allowTakeover) TAKEOVER_ALLOW else TAKEOVER_REFUSE,
        listOf(TAKEOVER_REFUSE, TAKEOVER_ALLOW),
        display = { stringResource(if (it == TAKEOVER_ALLOW) R.string.setting_takeover_allow else R.string.setting_takeover_refuse) }
    ) { choice -> viewModel.updateSettings { it.copy(allowTakeover = choice == TAKEOVER_ALLOW) } }
    CardDivider()
    KnownDevicesSetting(viewModel)
}

/**
 * Devices that have cast here, each allowed or blocked, and removing them all (two presses):
 * they are new again, confirming or entering a PIN the next time.
 */
@Composable
private fun KnownDevicesSetting(viewModel: AirPlayViewModel) {
    var devices by remember { mutableStateOf(viewModel.knownDevices()) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(RESET_CONFIRM_MS)
            armed = false
        }
    }
    SettingLine(stringResource(R.string.setting_devices)) {
        if (devices.isEmpty()) {
            Text(stringResource(R.string.setting_devices_none), color = Color.Gray, fontSize = 18.sp)
        } else {
            HomeButton(stringResource(if (armed) R.string.setting_forget_confirm else R.string.setting_forget_known), muted = !armed) {
                if (!armed) {
                    armed = true
                } else {
                    armed = false
                    viewModel.forgetDevices()
                    devices = emptyList()
                }
            }
        }
    }
    devices.forEach { device ->
        ChoiceSetting(
            device.name,
            device.allowed,
            listOf(true, false),
            display = { stringResource(if (it) R.string.device_allow else R.string.device_block) }
        ) { allowed ->
            viewModel.setDeviceAllowed(device.deviceId, allowed)
            devices = viewModel.knownDevices()
        }
    }
    Text(stringResource(R.string.setting_devices_note), color = Color.Gray, fontSize = 14.sp)
}

/** Each Auto names what it gives on this TV with the other settings. */
@Composable
private fun ColumnScope.MirroringPage(settings: ReceiverSettings, viewModel: AirPlayViewModel) {
    val autoResolution = remember(settings) {
        viewModel.mirroringProfile(settings.copy(resolution = ReceiverSettings.RESOLUTION_AUTO))
    }
    val resolutionAuto = stringResource(R.string.auto_with, "${autoResolution.height}p")
    ChoiceSetting(
        stringResource(R.string.setting_resolution),
        settings.resolution,
        ReceiverSettings.RESOLUTIONS,
        display = { if (it == ReceiverSettings.RESOLUTION_AUTO) resolutionAuto else settingValueLabel(it) }
    ) { viewModel.updateSettings { current -> current.copy(resolution = it) } }
    CardDivider()
    val autoFps = settings.copy(frameRate = ReceiverSettings.FRAME_RATE_AUTO).maxFps()
    val fpsAuto = stringResource(R.string.auto_with, stringResource(R.string.frame_rate_fps, autoFps))
    ChoiceSetting(
        stringResource(R.string.setting_frame_rate),
        settings.frameRate,
        ReceiverSettings.FRAME_RATES,
        display = { if (it == ReceiverSettings.FRAME_RATE_AUTO) fpsAuto else settingValueLabel(it) }
    ) { viewModel.updateSettings { current -> current.copy(frameRate = it) } }
    CardDivider()
    // H.265 only for 4K mirroring, on a 4K screen with a hardware HEVC decoder.
    val autoCodec = remember(settings) {
        viewModel.mirroringProfile(settings.copy(videoCodec = ReceiverSettings.CODEC_AUTO))
    }
    val codecAuto = stringResource(R.string.auto_with, if (autoCodec.h265) "H.265" else "H.264")
    ChoiceSetting(
        stringResource(R.string.setting_codec),
        settings.videoCodec,
        ReceiverSettings.VIDEO_CODECS,
        display = { if (it == ReceiverSettings.CODEC_AUTO) codecAuto else settingValueLabel(it) }
    ) { viewModel.updateSettings { current -> current.copy(videoCodec = it) } }
}

@Composable
private fun ColumnScope.PlaybackPage(settings: ReceiverSettings, viewModel: AirPlayViewModel) {
    SwitchSetting(stringResource(R.string.setting_stats), settings.showStats) { enabled ->
        viewModel.updateSettings { it.copy(showStats = enabled) }
    }
    CardDivider()
    SwitchSetting(stringResource(R.string.setting_lyrics), settings.showLyrics) { enabled ->
        viewModel.updateSettings { it.copy(showLyrics = enabled) }
    }
    Text(stringResource(R.string.setting_lyrics_note), color = Color.Gray, fontSize = 14.sp)
    CardDivider()
    val context = LocalContext.current
    SwitchSetting(stringResource(R.string.setting_open_on_cast), settings.openOnCast) { enabled ->
        viewModel.updateSettings { it.copy(openOnCast = enabled) }
        // Android lets an app open its screen from the background only with "Display over other apps".
        if (enabled && !ScreenLauncher.allowed(context)) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
        }
    }
    Text(stringResource(R.string.setting_open_on_cast_note), color = Color.Gray, fontSize = 14.sp)
    CardDivider()
    ChoiceSetting(stringResource(R.string.setting_picture), settings.pictureMode, ReceiverSettings.PICTURE_MODES) {
        viewModel.updateSettings { current -> current.copy(pictureMode = it) }
    }
    CardDivider()
    ChoiceSetting(stringResource(R.string.setting_screen_saver), settings.screenSaver, ReceiverSettings.SCREEN_SAVERS) {
        viewModel.updateSettings { current -> current.copy(screenSaver = it) }
    }
    Text(stringResource(R.string.setting_screen_saver_note), color = Color.Gray, fontSize = 14.sp)
}

@Composable
private fun ColumnScope.GeneralPage(
    settings: ReceiverSettings,
    viewModel: AirPlayViewModel,
    languageFocus: FocusRequester,
    onLanguageChanged: () -> Unit
) {
    ChoiceSetting(
        stringResource(R.string.setting_language),
        settings.language,
        ReceiverSettings.LANGUAGES,
        display = {
            when (it) {
                ReceiverSettings.LANGUAGE_ZH -> "中文"
                ReceiverSettings.LANGUAGE_EN -> "English"
                else -> stringResource(R.string.language_system)
            }
        },
        modifier = Modifier.focusRequester(languageFocus)
    ) { language ->
        viewModel.updateSettings { it.copy(language = language) }
        onLanguageChanged()
    }
    // Store builds (F-Droid) are updated by the store, so they don't look for updates themselves.
    if (BuildConfig.SELF_UPDATE) {
        CardDivider()
        SwitchSetting(stringResource(R.string.setting_check_updates), settings.checkUpdates) { viewModel.setCheckUpdates(it) }
        Text(stringResource(R.string.setting_check_updates_note), color = Color.Gray, fontSize = 14.sp)
        CardDivider()
        SwitchSetting(stringResource(R.string.setting_test_versions), settings.testVersions) { viewModel.setTestVersions(it) }
        Text(stringResource(R.string.setting_test_versions_note), color = Color.Gray, fontSize = 14.sp)
    }
    CardDivider()
    SwitchSetting(stringResource(R.string.setting_error_reports), settings.sendErrorReports) { viewModel.setSendErrorReports(it) }
    Text(stringResource(R.string.setting_error_reports_note), color = Color.Gray, fontSize = 14.sp)
    CardDivider()
    val usageStatsOn by viewModel.usageStatsOn.collectAsState()
    SwitchSetting(stringResource(R.string.setting_usage_stats), usageStatsOn) { viewModel.setUsageStats(it) }
    Text(stringResource(R.string.setting_usage_stats_note), color = Color.Gray, fontSize = 14.sp)
    CardDivider()
    // Two presses, so a stray OK doesn't wipe the device name and password.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(RESET_CONFIRM_MS)
            armed = false
        }
    }
    SettingLine(stringResource(R.string.setting_reset)) {
        HomeButton(stringResource(if (armed) R.string.setting_reset_confirm else R.string.setting_reset_action), muted = !armed) {
            if (!armed) {
                armed = true
            } else {
                armed = false
                val languageChanged = settings.language != ReceiverSettings.LANGUAGE_SYSTEM
                viewModel.resetSettings()
                if (languageChanged) onLanguageChanged()
            }
        }
    }
    Text(stringResource(R.string.setting_reset_note), color = Color.Gray, fontSize = 14.sp)
}

private const val RESET_CONFIRM_MS = 3000L

/** A page's settings on one card, like the home screen's info panel. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CARD_BACKGROUND)
            .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        content = content
    )
}

/** A thin line between the settings on a card. */
@Composable
private fun CardDivider() {
    Spacer(
        modifier = Modifier
            .padding(top = 6.dp, bottom = 6.dp, end = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(CARD_DIVIDER)
    )
}

/** As the home screen's info panel. */
private val CARD_BACKGROUND = Color(0x1FFFFFFF)
private val CARD_DIVIDER = Color(0x14FFFFFF)

@Composable
fun <T> ChoiceSetting(
    label: String,
    value: T,
    choices: List<T>,
    /** How a stored value is shown, in the TV's language. */
    display: @Composable (T) -> String = { settingValueLabel(it.toString()) },
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit
) {
    SettingLine(label) {
        SegmentedChoice(value, choices, display, modifier, onSelected)
    }
}

@Composable
fun DeviceNameSetting(value: String, modifier: Modifier = Modifier, onSaved: (String) -> Unit) {
    TextSetting(
        value = value,
        modifier = modifier,
        label = stringResource(R.string.setting_device_name),
        canSave = { it.isNotBlank() },
        onSaved = onSaved
    )
}

/**
 * A text field with a Save button beside it (not inside: a remote's left/right move the cursor
 * in a field, so a button within it can't be reached). Right at the end of the text moves to
 * Save, and the on-screen keyboard's Done key saves too. Save is grey while there's nothing
 * new to save.
 */
@Composable
private fun TextSetting(
    value: String,
    label: String,
    canSave: (String) -> Boolean,
    onSaved: (String) -> Unit,
    password: Boolean = false,
    filter: (String) -> String = { it },
    supportingText: (@Composable (String) -> Unit)? = null,
    isError: (String) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    var editing by remember(value) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // As on TV settings screens, focus alone doesn't open the keyboard (which covers half the
    // screen): the field is read-only until OK is pressed on it (or it is tapped).
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(typing) { if (typing) keyboard?.show() }
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) typing = true }
    }
    val savable = canSave(editing.text) && editing.text != value
    val save = {
        if (savable) onSaved(editing.text)
        keyboard?.hide()
        typing = false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = editing,
            onValueChange = { changed ->
                val text = filter(changed.text)
                editing = if (text == changed.text) changed else TextFieldValue(text, TextRange(text.length))
            },
            label = { Text(label) },
            supportingText = supportingText?.let { content -> { content(editing.text) } },
            isError = isError(editing.text),
            singleLine = true,
            readOnly = !typing,
            interactionSource = interaction,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.NumberPassword else KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = modifier
                .weight(1f)
                .onFocusChanged { if (!it.isFocused) typing = false }
                .onPreviewKeyEvent { event ->
                    val atEnd = editing.selection.collapsed && editing.selection.end == editing.text.length
                    val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (!typing && ok) {
                        if (event.type == KeyEventType.KeyUp) typing = true
                        true
                    } else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && atEnd) {
                        focusManager.moveFocus(FocusDirection.Right)
                    } else {
                        false
                    }
                }
        )
        Spacer(modifier = Modifier.width(16.dp))
        HomeButton(stringResource(R.string.action_save), muted = !savable) { save() }
    }
}

private const val TAKEOVER_REFUSE = "Refuse it"
private const val TAKEOVER_ALLOW = "Let it take over"

/** How a [ReceiverSettings.access] value is shown. */
@Composable
fun accessLabel(access: String): String = stringResource(
    when (access) {
        ReceiverSettings.ACCESS_CONFIRM -> R.string.access_confirm
        ReceiverSettings.ACCESS_PIN -> R.string.access_pin
        ReceiverSettings.ACCESS_PASSWORD -> R.string.access_password
        else -> R.string.access_open
    }
)

/**
 * Who may cast: anyone, a new device allowed on the TV, a new device entering a PIN shown on
 * the TV, or every device entering a password. The password only takes effect once a valid one is saved, so choosing it can't
 * lock everyone out by accident.
 */
@Composable
fun AccessSetting(settings: ReceiverSettings, viewModel: AirPlayViewModel) {
    var choosingPassword by remember { mutableStateOf(false) }
    // Shows "Password" while one is being chosen, though it applies only once saved.
    val shown = if (choosingPassword) ReceiverSettings.ACCESS_PASSWORD else settings.access
    ChoiceSetting(
        stringResource(R.string.setting_access),
        shown,
        ReceiverSettings.ACCESS_MODES,
        display = { accessLabel(it) }
    ) { choice ->
        choosingPassword = false
        when {
            choice != ReceiverSettings.ACCESS_PASSWORD -> viewModel.updateSettings { it.copy(access = choice) }
            ReceiverSettings.isValidPassword(settings.password) -> viewModel.updateSettings { it.copy(access = choice) }
            else -> choosingPassword = true  // enabled when a valid password is saved below
        }
    }
    when (shown) {
        ReceiverSettings.ACCESS_CONFIRM -> Text(stringResource(R.string.setting_confirm_note), color = Color.Gray, fontSize = 14.sp)
        ReceiverSettings.ACCESS_PIN -> Text(stringResource(R.string.setting_pin_note), color = Color.Gray, fontSize = 14.sp)
        ReceiverSettings.ACCESS_PASSWORD -> PasswordSetting(settings.password) { password ->
            choosingPassword = false
            viewModel.updateSettings { it.copy(password = password, access = ReceiverSettings.ACCESS_PASSWORD) }
        }
    }
}

/** The password senders must enter: at least [ReceiverSettings.MIN_PASSWORD_LENGTH] digits. */
@Composable
fun PasswordSetting(value: String, onSaved: (String) -> Unit) {
    TextSetting(
        value = value,
        label = stringResource(R.string.setting_password_field),
        canSave = ReceiverSettings::isValidPassword,
        onSaved = onSaved,
        password = true,
        filter = { it.filter(Char::isDigit) },
        supportingText = { text ->
            Text(
                if (!ReceiverSettings.isValidPassword(text)) stringResource(R.string.setting_password_too_short, ReceiverSettings.MIN_PASSWORD_LENGTH)
                else stringResource(R.string.setting_password_help)
            )
        },
        isError = { text -> text.isNotEmpty() && !ReceiverSettings.isValidPassword(text) }
    )
}

@Composable
fun SwitchSetting(label: String, checked: Boolean, modifier: Modifier = Modifier, onChanged: (Boolean) -> Unit) {
    SettingLine(label) {
        // Off and On side by side, like the other settings' choices.
        SegmentedChoice(checked, listOf(false, true), { stringResource(if (it) R.string.on else R.string.off) }, modifier, onChanged)
    }
}

/**
 * One setting: its name on the left, its choices on the right; on a narrow screen its choices
 * go below it (scrolling sideways if they're still too wide), so the name isn't squeezed.
 */
@Composable
private fun SettingLine(label: String, control: @Composable () -> Unit) {
    if (isNarrowScreen()) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(label, color = Color.White, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { control() }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        control()
    }
}
