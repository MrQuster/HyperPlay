package io.github.mrquster.hyperplay.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.device.IslandModePreference
import io.github.mrquster.hyperplay.device.IslandStyleOption
import io.github.mrquster.hyperplay.device.VendorBackgroundGuide
import io.github.mrquster.hyperplay.device.VendorSetupAction
import io.github.mrquster.hyperplay.ui.PlaybackViewModel
import io.github.mrquster.hyperplay.ui.components.*
import io.github.mrquster.hyperplay.ui.theme.HyperThemeMode
import io.github.mrquster.hyperplay.ui.theme.LocalGlassTokens
import io.github.mrquster.hyperplay.util.AppInfo
import io.github.mrquster.hyperplay.util.LocaleHelper
import io.github.mrquster.hyperplay.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    playbackViewModel: PlaybackViewModel,
    onOpenEqualizerTab: () -> Unit,
    onOpenSystemTab: () -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val scope = rememberCoroutineScope()

    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val deviceProfile by settingsViewModel.deviceProfile.collectAsStateWithLifecycle()
    val isEn = !LocaleHelper.isRussianActive(context, settings.languageCode)

    var showResetDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = settingsViewModel.prefsRepo.exportSettingsJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                Toast.makeText(context, context.getString(R.string.toast_exported), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                val ok = settingsViewModel.prefsRepo.importSettingsJson(json)
                Toast.makeText(
                    context,
                    context.getString(if (ok) R.string.toast_imported_ok else R.string.toast_imported_err),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = tokens.spacingLarge, vertical = tokens.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(tokens.spacingLarge)
    ) {
        Text(
            text = stringResource(R.string.nav_settings),
            color = tokens.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black
        )

        // 1. Секция: Воспроизведение и Аудио
        GlassSection(title = stringResource(R.string.set_sec_playback)) {
            SettingToggleRow(
                title = stringResource(R.string.set_gapless),
                checked = settings.gaplessEnabled,
                onCheckedChange = { v ->
                    scope.launch { settingsViewModel.prefsRepo.updatePlaybackFlags(gapless = v) }
                }
            )

            SettingSliderRow(
                title = stringResource(R.string.set_crossfade),
                valueBadge = if (settings.crossfadeDurationSec == 0) stringResource(R.string.sleep_off)
                else "${settings.crossfadeDurationSec} s",
                value = settings.crossfadeDurationSec.toFloat(),
                valueRange = 0f..12f,
                onValueChange = { v ->
                    scope.launch { settingsViewModel.prefsRepo.updatePlaybackFlags(crossfadeSec = v.roundToInt()) }
                }
            )

            SettingToggleRow(
                title = stringResource(R.string.set_replaygain),
                checked = settings.replayGainEnabled,
                onCheckedChange = { v ->
                    scope.launch { settingsViewModel.prefsRepo.updatePlaybackFlags(replayGain = v) }
                }
            )

            SettingToggleRow(
                title = stringResource(R.string.eq_skip_silence_label),
                checked = settings.skipSilenceEnabled,
                onCheckedChange = { v ->
                    playbackViewModel.setSkipSilence(v)
                }
            )

            SettingToggleRow(
                title = stringResource(R.string.set_auto_bt),
                checked = settings.autoResumeOnBluetooth,
                onCheckedChange = { v ->
                    scope.launch { settingsViewModel.prefsRepo.updatePlaybackFlags(autoBluetooth = v) }
                }
            )

            SettingToggleRow(
                title = stringResource(R.string.set_remember_queue),
                checked = settings.rememberQueueAndPosition,
                onCheckedChange = { v ->
                    scope.launch { settingsViewModel.prefsRepo.updatePlaybackFlags(rememberQueue = v) }
                }
            )

            GlassButton(
                text = stringResource(R.string.eq_screen_title),
                icon = Icons.Default.Equalizer,
                isAccent = true,
                onClick = onOpenEqualizerTab,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 2. Секция: Библиотека и индексация
        GlassSection(title = stringResource(R.string.set_sec_library)) {
            SettingSliderRow(
                title = stringResource(R.string.set_min_duration),
                valueBadge = "${settings.minTrackDurationSec} s",
                value = settings.minTrackDurationSec.toFloat(),
                valueRange = 0f..120f,
                onValueChange = { v ->
                    scope.launch { settingsViewModel.prefsRepo.updateMinDurationSec(v.roundToInt()) }
                }
            )

            SettingToggleRow(
                title = stringResource(R.string.set_hide_voice_notes),
                checked = settings.hideMessagingAndRingtoneFolders,
                onCheckedChange = { v ->
                    scope.launch {
                        settingsViewModel.prefsRepo.updatePlaybackFlags(hideSystemFolders = v)
                        playbackViewModel.rescanLibrary()
                    }
                }
            )

            GlassButton(
                text = stringResource(R.string.btn_rescan),
                icon = Icons.Default.Refresh,
                onClick = { playbackViewModel.rescanLibrary() },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 3. Секция: Внешний вид и Стекло Liquid Glass
        GlassSection(title = stringResource(R.string.set_sec_appearance)) {
            Text(
                text = stringResource(R.string.theme_label),
                color = tokens.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    HyperThemeMode.DARK to R.string.theme_dark,
                    HyperThemeMode.LIGHT to R.string.theme_light,
                    HyperThemeMode.AMOLED to R.string.theme_amoled,
                    HyperThemeMode.SYSTEM to R.string.theme_system
                ).chunked(2).forEach { _ -> }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassChip(
                    label = stringResource(R.string.theme_dark),
                    selected = settings.themeMode == HyperThemeMode.DARK,
                    onClick = { settingsViewModel.setThemeMode(HyperThemeMode.DARK) },
                    modifier = Modifier.weight(1f)
                )
                GlassChip(
                    label = stringResource(R.string.theme_light),
                    selected = settings.themeMode == HyperThemeMode.LIGHT,
                    onClick = { settingsViewModel.setThemeMode(HyperThemeMode.LIGHT) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassChip(
                    label = stringResource(R.string.theme_amoled),
                    selected = settings.themeMode == HyperThemeMode.AMOLED,
                    onClick = { settingsViewModel.setThemeMode(HyperThemeMode.AMOLED) },
                    modifier = Modifier.weight(1f)
                )
                GlassChip(
                    label = stringResource(R.string.theme_system),
                    selected = settings.themeMode == HyperThemeMode.SYSTEM,
                    onClick = { settingsViewModel.setThemeMode(HyperThemeMode.SYSTEM) },
                    modifier = Modifier.weight(1f)
                )
            }

            SettingToggleRow(
                title = stringResource(R.string.set_artwork_colors),
                checked = settings.useArtworkAccent,
                onCheckedChange = { settingsViewModel.setUseArtworkAccent(it) }
            )

            SettingSliderRow(
                title = stringResource(R.string.blur_radius_label),
                valueBadge = "${settings.blurIntensityDp.roundToInt()} dp",
                value = settings.blurIntensityDp,
                valueRange = 16f..60f,
                onValueChange = { settingsViewModel.setBlurDp(it) }
            )

            SettingSliderRow(
                title = stringResource(R.string.glass_density_label),
                valueBadge = "${(settings.glassAlpha * 100).roundToInt()}%",
                value = settings.glassAlpha,
                valueRange = 0.08f..0.40f,
                onValueChange = { settingsViewModel.setGlassAlpha(it) }
            )

            SettingToggleRow(
                title = stringResource(R.string.set_show_visualizer),
                checked = settings.showVisualizer,
                onCheckedChange = { settingsViewModel.setShowVisualizer(it) }
            )

            SettingToggleRow(
                title = stringResource(R.string.powersave_glass_toggle),
                checked = settings.powerSaveGlass,
                onCheckedChange = { settingsViewModel.setPowerSaveGlass(it) }
            )

            Text(
                text = stringResource(R.string.language_label),
                color = tokens.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassChip(
                    label = stringResource(R.string.lang_system),
                    selected = settings.languageCode == "system",
                    onClick = { settingsViewModel.setLanguage("system") },
                    modifier = Modifier.weight(1f)
                )
                GlassChip(
                    label = stringResource(R.string.lang_ru),
                    selected = settings.languageCode == "ru",
                    onClick = { settingsViewModel.setLanguage("ru") },
                    modifier = Modifier.weight(1f)
                )
                GlassChip(
                    label = stringResource(R.string.lang_en),
                    selected = settings.languageCode == "en",
                    onClick = { settingsViewModel.setLanguage("en") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 4. Секция: Суперостров (Hyper Island) и Уведомления
        GlassSection(title = stringResource(R.string.sec_island)) {
            Text(
                text = stringResource(R.string.island_routing_label),
                color = tokens.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp
            )
            IslandModePreference.entries.forEach { pref ->
                GlassChip(
                    label = if (isEn) pref.labelEn else pref.labelRu,
                    selected = settings.islandRoutingPreference == pref,
                    onClick = { settingsViewModel.setIslandRoute(pref) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = stringResource(R.string.island_style_label),
                color = tokens.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp
            )
            IslandStyleOption.entries.forEach { st ->
                GlassChip(
                    label = if (isEn) st.labelEn else st.labelRu,
                    selected = settings.islandStyle == st,
                    onClick = { settingsViewModel.setIslandStyle(st) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingToggleRow(
                title = stringResource(R.string.island_lockscreen_toggle),
                checked = settings.islandAllowLockscreen,
                onCheckedChange = { settingsViewModel.setIslandLockscreen(it) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    text = stringResource(R.string.sys_btn_island_selftest),
                    icon = Icons.Default.FactCheck,
                    isAccent = true,
                    onClick = {
                        settingsViewModel.runIslandSelfTest()
                        onOpenSystemTab()
                    },
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = stringResource(R.string.island_open_notif_btn),
                    icon = Icons.Default.NotificationsActive,
                    onClick = {
                        VendorBackgroundGuide.executeActionSafely(
                            context,
                            VendorSetupAction.OPEN_NOTIFICATIONS_AND_ISLAND
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 5. Адаптивный мастер настройки фоновой работы (Xiaomi HyperOS / Samsung / OPlus / AOSP)
        val vendorTitle = remember(deviceProfile.osFamily, isEn) {
            VendorBackgroundGuide.getTitleForFamily(deviceProfile.osFamily, isEn)
        }
        val vendorSteps = remember(deviceProfile) {
            VendorBackgroundGuide.getStepsForProfile(deviceProfile)
        }
        GlassSection(title = vendorTitle) {
            vendorSteps.forEachIndexed { idx, step ->
                Text(
                    text = if (isEn) step.descriptionEn else step.descriptionRu,
                    color = tokens.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                GlassButton(
                    text = if (isEn) step.stepTitleEn else step.stepTitleRu,
                    icon = if (idx == 0) Icons.Default.RocketLaunch else Icons.Default.BatteryChargingFull,
                    isAccent = idx == 0,
                    onClick = { VendorBackgroundGuide.executeActionSafely(context, step.actionType) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 6. Резервное копирование и сброс
        GlassSection(title = stringResource(R.string.sec_data)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    text = stringResource(R.string.btn_export_json),
                    icon = Icons.Default.UploadFile,
                    onClick = { exportLauncher.launch("hyperplay_settings.json") },
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = stringResource(R.string.btn_import_json),
                    icon = Icons.Default.Download,
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.weight(1f)
                )
            }
            GlassButton(
                text = stringResource(R.string.btn_reset_settings),
                icon = Icons.Default.RestartAlt,
                isDanger = true,
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 7. Секция: «О приложении и Авторе» (MrQuster)
        GlassSection(title = stringResource(R.string.sec_about_author)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(tokens.accentColor.copy(alpha = 0.22f))
                            .border(1.dp, tokens.accentColor.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = AppInfo.AUTHOR_NAME,
                            tint = tokens.accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = AppInfo.AUTHOR_NAME,
                            color = tokens.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.about_author_role),
                            color = tokens.accentColor,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                GlassButton(
                    text = stringResource(R.string.btn_author_github),
                    icon = Icons.Default.OpenInNew,
                    isAccent = true,
                    onClick = { AppInfo.openUrlSafely(context, AppInfo.GITHUB_PROFILE_URL) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${AppInfo.APP_NAME} • " + stringResource(
                        R.string.about_version_fmt,
                        AppInfo.VERSION_NAME,
                        AppInfo.VERSION_CODE
                    ),
                    color = tokens.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        R.string.about_copyright_fmt,
                        AppInfo.COPYRIGHT_YEAR,
                        AppInfo.AUTHOR_NAME
                    ),
                    color = tokens.textSecondary,
                    fontSize = 12.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    text = stringResource(R.string.btn_source_code),
                    icon = Icons.Default.Code,
                    onClick = { AppInfo.openUrlSafely(context, AppInfo.REPO_URL) },
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = stringResource(R.string.btn_github_releases),
                    icon = Icons.Default.Download,
                    onClick = { AppInfo.openUrlSafely(context, AppInfo.RELEASES_URL) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(tokens.listBottomPadding))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.confirm_reset_title)) },
            text = { Text(stringResource(R.string.confirm_reset_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settingsViewModel.prefsRepo.resetAllSettings() }
                    showResetDialog = false
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = LocalGlassTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.minTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = tokens.textPrimary,
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    valueBadge: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val tokens = LocalGlassTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = tokens.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.accentColor.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = valueBadge,
                    color = tokens.accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
        GlassSlider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}
