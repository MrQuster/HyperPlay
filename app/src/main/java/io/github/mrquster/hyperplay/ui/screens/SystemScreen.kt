package io.github.mrquster.hyperplay.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.audio.AudioDiagnosticsEngine
import io.github.mrquster.hyperplay.audio.PlaybackEventLogger
import io.github.mrquster.hyperplay.device.*
import io.github.mrquster.hyperplay.island.IslandEventLogger
import io.github.mrquster.hyperplay.ui.components.GlassButton
import io.github.mrquster.hyperplay.ui.components.GlassCard
import io.github.mrquster.hyperplay.ui.components.GlassSwitch
import io.github.mrquster.hyperplay.ui.theme.LocalGlassTokens
import io.github.mrquster.hyperplay.util.LocaleHelper
import io.github.mrquster.hyperplay.viewmodel.SettingsViewModel
import java.util.Locale

@Composable
fun SystemScreen(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profile by viewModel.deviceProfile.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningProfile.collectAsStateWithLifecycle()
    val islandLogs by IslandEventLogger.entriesFlow.collectAsStateWithLifecycle()
    val selfTestItems by IslandEventLogger.selfTestFlow.collectAsStateWithLifecycle()

    val abSwitches by AudioDiagnosticsEngine.abSwitches.collectAsStateWithLifecycle()
    val audioMetrics by AudioDiagnosticsEngine.metrics.collectAsStateWithLifecycle()
    val audioLogs by AudioDiagnosticsEngine.logsFlow.collectAsStateWithLifecycle()
    val playbackEvents by PlaybackEventLogger.eventsFlow.collectAsStateWithLifecycle()
    val dropoutIncidents by PlaybackEventLogger.incidentsFlow.collectAsStateWithLifecycle()
    val stressTestState by PlaybackEventLogger.stressTestState.collectAsStateWithLifecycle()

    val isEn = !LocaleHelper.isRussianActive(context, settings.languageCode)
    val plan = remember(profile, settings.islandRoutingPreference) {
        FeatureRegistry.resolvePlan(profile, settings.islandRoutingPreference, false)
    }

    var showAudioAbExpanded by remember { mutableStateOf(true) }
    var showAudioLogExpanded by remember { mutableStateOf(false) }
    var showPlaybackTimelineExpanded by remember { mutableStateOf(false) }
    var showIslandLogExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = tokens.spacingLarge, vertical = tokens.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(tokens.spacingLarge)
    ) {
        // 1. Шапка «Моё устройство»
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 30.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${profile.brand} ${profile.model}",
                            color = tokens.textPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.sys_manufacturer_codename_fmt, profile.vendor, profile.deviceCodename),
                            color = tokens.textSecondary,
                            fontSize = 12.5.sp
                        )
                    }
                    if (isScanning) {
                        CircularProgressIndicator(color = tokens.accentColor, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(tokens.accentColor.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = tokens.accentColor)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BadgePill(
                        text = "${if (isEn) profile.osFamily.displayNameEn else profile.osFamily.displayNameRu} (${profile.osVersion})",
                        accent = tokens.accentColor
                    )
                    BadgePill(
                        text = "Android ${profile.androidRelease} • API ${profile.apiLevelFullFormatted}",
                        accent = tokens.secondaryAccent
                    )
                }
            }
        }

        // 2.0. Раздел: Прерывания и Журнал воспроизведения (300 событий)
        SysSectionTitle(stringResource(R.string.drop_sec_title))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val hasDropouts = dropoutIncidents.isNotEmpty()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (hasDropouts) tokens.dangerColor.copy(alpha = 0.18f)
                            else tokens.accentColor.copy(alpha = 0.15f)
                        )
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (!hasDropouts) stringResource(R.string.drop_summary_zero)
                            else stringResource(
                                R.string.drop_summary_count_fmt,
                                dropoutIncidents.size,
                                dropoutIncidents.first().timestampFormatted
                            ),
                            color = if (hasDropouts) tokens.dangerColor else tokens.accentColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.5.sp
                        )
                        if (hasDropouts) {
                            val latest = dropoutIncidents.first()
                            Text(
                                text = if (isEn) latest.reason.labelEn else latest.reason.labelRu,
                                color = tokens.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp
                            )
                            Text(
                                text = latest.detail,
                                color = tokens.textSecondary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (isEn) latest.reason.hintEn else latest.reason.hintRu,
                                color = Color(0xFFFFBE0B),
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        text = if (stressTestState.isRunning) stringResource(R.string.drop_stress_stop_btn)
                        else stringResource(R.string.drop_stress_start_btn),
                        icon = Icons.Default.Timer,
                        isAccent = !stressTestState.isRunning,
                        isDanger = stressTestState.isRunning,
                        onClick = { PlaybackEventLogger.startDropoutStressTest(5) },
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = stringResource(R.string.action_clear),
                        icon = Icons.Default.DeleteSweep,
                        onClick = { PlaybackEventLogger.clearAll() }
                    )
                }

                if (stressTestState.summaryText.isNotEmpty()) {
                    Text(
                        text = stressTestState.summaryText,
                        color = tokens.accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                DiagAbToggleRow(
                    title = stringResource(R.string.diag_pause_scanner),
                    checked = abSwitches.pauseScannerDuringPlayback,
                    onCheckedChange = { v ->
                        AudioDiagnosticsEngine.updateSwitches { it.copy(pauseScannerDuringPlayback = v) }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { showPlaybackTimelineExpanded = !showPlaybackTimelineExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.drop_timeline_title_fmt, playbackEvents.size),
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Icon(
                        imageVector = if (showPlaybackTimelineExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = tokens.accentColor
                    )
                }

                AnimatedVisibility(visible = showPlaybackTimelineExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.52f))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = if (playbackEvents.isEmpty()) "No playback events yet."
                                else playbackEvents.take(40).joinToString("\n") { e ->
                                    val mark = if (e.isDropoutMarker) "⚠️ " else ""
                                    "${e.wallTimeFormatted} $mark[${e.category}] ${e.message}"
                                },
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            )
                        }
                        GlassButton(
                            text = stringResource(R.string.action_copy),
                            icon = Icons.Default.ContentCopy,
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "HyperPlay Playback Log",
                                        PlaybackEventLogger.formatPlaybackLogReport(!isEn)
                                    )
                                )
                                Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 2. НОВЫЙ РАЗДЕЛ: Диагностика звука (A/B переключатели, Пики/RMS, Клиппинг, Тестовый сигнал)
        SysSectionTitle(stringResource(R.string.diag_audio_sec_title))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.diag_audio_hint),
                    color = tokens.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                // Главная кнопка «Чистый режим (Bypass All)» и кнопка «Безопасный режим»
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        text = if (abSwitches.pureBypassAll) stringResource(R.string.diag_pure_mode_on)
                        else stringResource(R.string.diag_pure_mode_off),
                        icon = Icons.Default.Shield,
                        isAccent = abSwitches.pureBypassAll,
                        onClick = { AudioDiagnosticsEngine.togglePureBypassMode() },
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = stringResource(R.string.diag_safe_preset_btn),
                        icon = Icons.Default.Verified,
                        onClick = { AudioDiagnosticsEngine.applyConservativeSafeMode() },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Встроенный генератор эталонного сигнала (Синус 1 кГц −12 dBFS / Розовый шум)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        text = if (audioMetrics.isTestTonePlaying && audioMetrics.activeTestToneType.contains("SINE"))
                            stringResource(R.string.diag_stop_signal)
                        else stringResource(R.string.diag_sine_1khz_btn),
                        icon = Icons.Default.GraphicEq,
                        isAccent = audioMetrics.isTestTonePlaying && audioMetrics.activeTestToneType.contains("SINE"),
                        onClick = { AudioDiagnosticsEngine.playReferenceSignal(isPinkNoise = false) },
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = if (audioMetrics.isTestTonePlaying && audioMetrics.activeTestToneType.contains("PINK"))
                            stringResource(R.string.diag_stop_signal)
                        else stringResource(R.string.diag_pink_noise_btn),
                        icon = Icons.Default.Waves,
                        isAccent = audioMetrics.isTestTonePlaying && audioMetrics.activeTestToneType.contains("PINK"),
                        onClick = { AudioDiagnosticsEngine.playReferenceSignal(isPinkNoise = true) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Панель телеметрии в реальном времени (1 Гц, 0 нагрузки на CPU)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${audioMetrics.sampleRateHz} Hz • ${audioMetrics.channelCount} ch • ${audioMetrics.encodingName} • ${audioMetrics.decoderName}",
                        color = tokens.accentColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "IN:  Peak %6.1f dBFS | RMS %6.1f dBFS",
                            audioMetrics.inputPeakDbFs,
                            audioMetrics.inputRmsDbFs
                        ),
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "OUT: Peak %6.1f dBFS | RMS %6.1f dBFS",
                            audioMetrics.outputPeakDbFs,
                            audioMetrics.outputRmsDbFs
                        ),
                        color = if (audioMetrics.outputPeakDbFs > -0.2f) tokens.dangerColor else Color(0xFFE2E8F0),
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Headroom: %5.1f dB | Limiter GR: %5.1f dB | CPU: %4.1f%%",
                            audioMetrics.autoHeadroomAttenuationDb,
                            audioMetrics.limiterGainReductionDb,
                            audioMetrics.estimatedAppCpuPercent
                        ),
                        color = tokens.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hasIssues = audioMetrics.clippedSamplesCount > 0L || audioMetrics.underrunCount > 0
                        Text(
                            text = "Clipped: ${audioMetrics.clippedSamplesCount} • Underruns: ${audioMetrics.underrunCount}",
                            color = if (hasIssues) tokens.dangerColor else tokens.accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = stringResource(R.string.action_clear),
                            color = tokens.accentColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { AudioDiagnosticsEngine.resetCounters() }
                        )
                    }
                }

                // Раскрывающийся список 12 независимых A/B тумблеров
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { showAudioAbExpanded = !showAudioAbExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.diag_ab_switches_title),
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Icon(
                        imageVector = if (showAudioAbExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = tokens.accentColor
                    )
                }

                AnimatedVisibility(visible = showAudioAbExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiagAbToggleRow(
                            title = stringResource(R.string.diag_sw_dsp),
                            checked = abSwitches.softwareDspEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, softwareDspEnabled = v) }
                            }
                        )
                        DiagAbToggleRow(
                            title = stringResource(R.string.diag_auto_headroom),
                            checked = abSwitches.autoHeadroomEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, autoHeadroomEnabled = v) }
                            }
                        )
                        DiagAbToggleRow(
                            title = stringResource(R.string.diag_dither_16bit),
                            checked = abSwitches.dither16BitEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, dither16BitEnabled = v) }
                            }
                        )
                        DiagAbToggleRow(
                            title = stringResource(R.string.diag_hw_eq),
                            checked = abSwitches.hardwareEqualizerEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, hardwareEqualizerEnabled = v) }
                            }
                        )
                        DiagAbToggleRow(
                            title = stringResource(R.string.diag_hw_bass),
                            checked = abSwitches.hardwareBassBoostEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, hardwareBassBoostEnabled = v) }
                            }
                        )
                        DiagAbToggleRow(
                            title = stringResource(R.string.diag_hw_virt),
                            checked = abSwitches.hardwareVirtualizerEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, hardwareVirtualizerEnabled = v) }
                            }
                        )
                        DiagAbToggleRow(
                            title = stringResource(R.string.diag_hw_loudness),
                            checked = abSwitches.loudnessEnhancerEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, loudnessEnhancerEnabled = v) }
                            }
                        )
                        DiagAbToggleRow(
                            title = stringResource(R.string.set_replaygain),
                            checked = abSwitches.replayGainEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, replayGainEnabled = v) }
                            }
                        )
                        DiagAbToggleRow(
                            title = stringResource(R.string.set_crossfade),
                            checked = abSwitches.crossfadeEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, crossfadeEnabled = v) }
                            }
                        )
                        DiagAbToggleRow(
                            title = stringResource(R.string.eq_skip_silence_label),
                            checked = abSwitches.skipSilenceEnabled && !abSwitches.pureBypassAll,
                            onCheckedChange = { v ->
                                AudioDiagnosticsEngine.updateSwitches { it.copy(pureBypassAll = false, skipSilenceEnabled = v) }
                            }
                        )
                    }
                }

                // Журнал диагностики звука (200 записей)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { showAudioLogExpanded = !showAudioLogExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.diag_audio_log_fmt, audioLogs.size),
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Icon(
                        imageVector = if (showAudioLogExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = tokens.accentColor
                    )
                }

                AnimatedVisibility(visible = showAudioLogExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.50f))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = if (audioLogs.isEmpty()) "No audio events logged yet."
                            else audioLogs.take(25).joinToString("\n") { "${it.timeFormatted} [${it.event}] ${it.detail}" },
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // 3. Система и прошивка
        SysSectionTitle(stringResource(R.string.sys_sec_os))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyValueRow(stringResource(R.string.sys_os_family), "${if (isEn) profile.osFamily.displayNameEn else profile.osFamily.displayNameRu} (${profile.osVersion})")
                KeyValueRow(stringResource(R.string.sys_rom_region), profile.romRegion)
                KeyValueRow(stringResource(R.string.sys_android_ver), "${profile.androidRelease} (API ${profile.apiLevel})")
                KeyValueRow(stringResource(R.string.sys_sec_patch), profile.securityPatch)
                KeyValueRow(stringResource(R.string.sys_build_id), profile.displayBuildId)
                KeyValueRow(
                    stringResource(R.string.sys_rom_integrity),
                    if (profile.isLikelyCustomRom) stringResource(R.string.sys_rom_custom) else stringResource(R.string.sys_rom_stock)
                )
            }
        }

        // 4. Железо и экран
        SysSectionTitle(stringResource(R.string.sys_sec_hw))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            val hw = profile.hardwareSpecs
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyValueRow(stringResource(R.string.sys_soc), "${hw.socManufacturer} ${hw.socModel} (${hw.cpuCores} cores)")
                KeyValueRow(stringResource(R.string.sys_ram), "${hw.availableRamMb} MB / ${hw.totalRamMb} MB")
                KeyValueRow(stringResource(R.string.sys_screen), "${hw.screenResolutionPx} • ${hw.screenDensityDpi} dpi")
                KeyValueRow(stringResource(R.string.sys_refresh_hdr), "${hw.refreshRateHz} Hz • HDR: ${hw.hdrSupported}")
                KeyValueRow(stringResource(R.string.sys_form_factor), if (isEn) profile.formFactor.labelEn else profile.formFactor.labelRu)
                KeyValueRow(stringResource(R.string.sys_cutout), "${profile.cutoutStatus.symbol} ${profile.cutoutStatus.labelRu}")
            }
        }

        // 5. Суперостров: Самопроверка и Журнал
        SysSectionTitle(stringResource(R.string.sys_sec_island))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KeyValueRow(stringResource(R.string.sys_island_strategy), if (isEn) plan.selectedStrategy.titleEn else plan.selectedStrategy.titleRu)
                KeyValueRow(stringResource(R.string.sys_island_focus_status), "${profile.focusNotificationStatus.symbol} ${profile.focusNotificationStatus.labelRu}")

                GlassButton(
                    text = stringResource(R.string.sys_btn_island_selftest),
                    icon = Icons.Default.FactCheck,
                    isAccent = true,
                    onClick = { viewModel.runIslandSelfTest() },
                    modifier = Modifier.fillMaxWidth()
                )

                if (selfTestItems.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.42f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stringResource(R.string.sys_island_selftest_header), color = tokens.accentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        selfTestItems.forEach { item ->
                            val sym = if (item.passed) "✓" else "✗"
                            val col = if (item.passed) tokens.accentColor else tokens.dangerColor
                            Text(text = "$sym ${item.title}", color = col, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            Text(text = item.detail, color = tokens.textSecondary, fontSize = 11.5.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { showIslandLogExpanded = !showIslandLogExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sys_island_log_title_fmt, islandLogs.size),
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Icon(
                        imageVector = if (showIslandLogExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = tokens.accentColor
                    )
                }

                AnimatedVisibility(visible = showIslandLogExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.50f))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = if (islandLogs.isEmpty()) stringResource(R.string.sys_island_log_empty)
                                else islandLogs.take(25).joinToString("\n") { e ->
                                    "${e.formattedTime} ${if (e.isSuccess) "✓" else "✗"} [#${e.seqId}] ${e.action} | ${e.trackTitle} (${e.progressPercent}%) -> ${e.detail}"
                                },
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            GlassButton(
                                text = stringResource(R.string.action_copy),
                                icon = Icons.Default.ContentCopy,
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("HyperPlay Island Log", IslandEventLogger.formatPlainText()))
                                    Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            GlassButton(
                                text = stringResource(R.string.action_clear),
                                icon = Icons.Default.DeleteSweep,
                                isDanger = true,
                                onClick = { IslandEventLogger.clear() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 6. Матрица возможностей
        SysSectionTitle(stringResource(R.string.sys_sec_caps_fmt, profile.capabilities.size))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                profile.capabilityEvaluations.forEachIndexed { idx, ev ->
                    if (idx > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val col = when (ev.status) {
                            TriStateStatus.SUPPORTED -> tokens.accentColor
                            TriStateStatus.UNKNOWN -> Color(0xFFFFBE0B)
                            TriStateStatus.UNSUPPORTED -> Color(0xFF94A3B8)
                        }
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(col.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = ev.status.symbol, color = col, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = if (isEn) ev.capability.titleEn else ev.capability.titleRu, color = tokens.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = if (isEn) ev.reasonEn else ev.reasonRu, color = tokens.textSecondary, fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }

        // 7. Экспорт полного отчёта (Система + Диагностика звука + Суперостров)
        SysSectionTitle(stringResource(R.string.sys_sec_manage))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(
                    text = stringResource(R.string.sys_btn_copy_report),
                    icon = Icons.Default.ContentCopy,
                    isAccent = true,
                    onClick = {
                        val fullReport = viewModel.buildDiagnosticReport() + "\n" + AudioDiagnosticsEngine.formatReportSection() + "\n" + PlaybackEventLogger.formatPlaybackLogReport(!isEn)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("HyperPlay Full Diagnostic Report", fullReport))
                        Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(172.dp))
    }
}

@Composable
private fun DiagAbToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = LocalGlassTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = tokens.textPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(end = 10.dp)
        )
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SysSectionTitle(title: String) {
    val tokens = LocalGlassTokens.current
    Text(
        text = title,
        color = tokens.accentColor,
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold
    )
}

@Composable
private fun BadgePill(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.20f))
            .border(1.dp, accent.copy(alpha = 0.60f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    val tokens = LocalGlassTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = tokens.textSecondary, fontSize = 11.5.sp)
        Text(text = value, color = tokens.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
