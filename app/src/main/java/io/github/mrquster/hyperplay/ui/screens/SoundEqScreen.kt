package io.github.mrquster.hyperplay.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.audio.AudioEffectsController
import io.github.mrquster.hyperplay.audio.BuiltInEqPreset
import io.github.mrquster.hyperplay.ui.PlaybackViewModel
import io.github.mrquster.hyperplay.ui.components.*
import io.github.mrquster.hyperplay.ui.theme.LocalGlassTokens
import kotlin.math.roundToInt

@Composable
fun SoundEqScreen(
    effectsController: AudioEffectsController,
    playbackViewModel: PlaybackViewModel
) {
    val tokens = LocalGlassTokens.current
    val eqState by effectsController.uiState.collectAsStateWithLifecycle()
    val playbackSettings by playbackViewModel.playbackSettings.collectAsStateWithLifecycle()
    val profile = eqState.activeProfile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = tokens.spacingLarge, vertical = tokens.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(tokens.spacingLarge)
    ) {
        // 1. Шапка и кнопка «Сбросить»
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = stringResource(R.string.eq_screen_title),
                    color = tokens.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.eq_screen_subtitle),
                    color = tokens.textSecondary,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            GlassButton(
                text = stringResource(R.string.eq_reset_btn),
                icon = Icons.Default.Refresh,
                onClick = { effectsController.resetCurrentProfile() }
            )
        }

        // 2. Карточка активного звукового профиля устройства (Динамик / Bluetooth / USB-ЦАП)
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val devIcon = when (profile.deviceType) {
                    "BLUETOOTH" -> Icons.Default.Headphones
                    "USB", "WIRED" -> Icons.Default.Cable
                    else -> Icons.Default.Speaker
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(tokens.accentColor.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(devIcon, contentDescription = null, tint = tokens.accentColor)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.eq_active_profile_fmt, profile.deviceName),
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.eq_session_route_fmt, eqState.activeAudioSessionId, eqState.bassRouteName),
                        color = tokens.textSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 3. Горизонтальная лента пресетов эквалайзера
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.eq_presets_label),
                    color = tokens.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BuiltInEqPreset.entries.forEach { preset ->
                        GlassChip(
                            label = stringResource(preset.titleRes),
                            selected = profile.presetName == preset.name,
                            onClick = { effectsController.selectPreset(preset) }
                        )
                    }
                }
            }
        }

        // 4. 5-полосный вертикальный эквалайзер + Предусилитель (Preamp)
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    eqState.bands.forEach { band ->
                        val span = (band.maxMb - band.minMb).coerceAtLeast(1)
                        val frac = ((band.currentMb - band.minMb).toFloat() / span).coerceIn(0f, 1f)
                        val dbVal = band.currentMb / 100f
                        val dbText = if (dbVal >= 0f) "+${"%.1f".format(dbVal)}" else "%.1f".format(dbVal)
                        val freqLabel = if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000}k" else "${band.centerFreqHz}"

                        VerticalEqBandSlider(
                            freqLabel = freqLabel,
                            gainText = dbText,
                            fraction = frac,
                            onFractionChange = { newFrac ->
                                val newMb = (band.minMb + newFrac * span).roundToInt()
                                effectsController.setBandLevelMb(band.index, newMb)
                            }
                        )
                    }
                }

                val preampText = if (profile.preampDb >= 0f) "+${"%.1f".format(profile.preampDb)} dB"
                else "${"%.1f".format(profile.preampDb)} dB"
                EqSliderRow(
                    title = stringResource(R.string.eq_preamp_label),
                    valueBadge = preampText,
                    value = profile.preampDb,
                    valueRange = -12f..12f,
                    onValueChange = { effectsController.setPreampDb(it) }
                )
            }
        }

        // 5. Бас (Biquad Low-Shelf), Объёмный звук (Mid/Side), Усиление громкости и Баланс Л/П
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val bassDb = (profile.bassStrength / 1000f) * 12f
                EqSliderRow(
                    title = stringResource(R.string.eq_bass_label),
                    valueBadge = "${profile.bassStrength / 10}% (+${"%.1f".format(bassDb)} dB)",
                    value = profile.bassStrength.toFloat(),
                    valueRange = 0f..1000f,
                    onValueChange = { effectsController.setBassStrength(it.roundToInt()) }
                )

                EqSliderRow(
                    title = stringResource(R.string.eq_surround_label),
                    valueBadge = "${profile.virtualizerStrength / 10}%",
                    value = profile.virtualizerStrength.toFloat(),
                    valueRange = 0f..1000f,
                    onValueChange = { effectsController.setVirtualizerStrength(it.roundToInt()) }
                )

                EqSliderRow(
                    title = stringResource(R.string.eq_volume_boost_label),
                    valueBadge = "${profile.volumeBoostPercent}%",
                    value = profile.volumeBoostPercent.toFloat(),
                    valueRange = 50f..400f,
                    onValueChange = { effectsController.setVolumeBoostPercent(it.roundToInt()) }
                )

                val panLabel = when {
                    profile.balanceLeftRight < -0.04f -> "L ${"%.2f".format(profile.balanceLeftRight)}"
                    profile.balanceLeftRight > 0.04f -> "R +${"%.2f".format(profile.balanceLeftRight)}"
                    else -> "Center (0.0)"
                }
                EqSliderRow(
                    title = stringResource(R.string.eq_balance_label),
                    valueBadge = panLabel,
                    value = profile.balanceLeftRight,
                    valueRange = -1f..1f,
                    onValueChange = { effectsController.setBalance(it) }
                )

                // Переключатели: Моно, Мягкий лимитер и Пропуск тишины
                EqToggleRow(
                    title = stringResource(R.string.eq_mono_label),
                    checked = profile.monoEnabled,
                    onCheckedChange = { effectsController.setMono(it) }
                )

                EqToggleRow(
                    title = stringResource(R.string.eq_soft_limiter_label),
                    checked = profile.softLimiterEnabled,
                    onCheckedChange = { effectsController.setSoftLimiter(it) }
                )

                EqToggleRow(
                    title = stringResource(R.string.eq_skip_silence_label),
                    checked = playbackSettings.skipSilenceEnabled,
                    onCheckedChange = { enabled ->
                        playbackViewModel.setSkipSilence(enabled)
                    }
                )
            }
        }

        // 6. Карточка самопроверки аудиоэффектов («Проверить эффекты»)
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(
                    text = stringResource(R.string.eq_verify_effects_btn),
                    icon = Icons.Default.FactCheck,
                    isAccent = true,
                    onClick = { effectsController.runEffectsSelfTest() },
                    modifier = Modifier.fillMaxWidth()
                )

                if (eqState.selfTestResults.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.40f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        eqState.selfTestResults.forEach { item ->
                            val badge = if (item.passed) "✓" else "✗"
                            val color = if (item.passed) tokens.accentColor else tokens.dangerColor
                            Text(
                                text = "$badge ${item.name}",
                                color = color,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp
                            )
                            Text(
                                text = item.routeDescription,
                                color = tokens.textSecondary,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(tokens.listBottomPadding))
    }
}

@Composable
private fun EqSliderRow(
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
        GlassSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
private fun EqToggleRow(
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
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun VerticalEqBandSlider(
    freqLabel: String,
    gainText: String,
    fraction: Float,
    onFractionChange: (Float) -> Unit
) {
    val tokens = LocalGlassTokens.current
    val haptics = LocalHapticFeedback.current
    val currentOnChange by rememberUpdatedState(onFractionChange)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = gainText,
            color = tokens.accentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Canvas(
            modifier = Modifier
                .width(42.dp)
                .height(152.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val f = (1f - offset.y / size.height).coerceIn(0f, 1f)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnChange(f)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val f = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        currentOnChange(f)
                    }
                }
        ) {
            val trackW = 11.dp.toPx()
            val cx = size.width / 2f
            val pad = 12.dp.toPx()
            val usableH = size.height - pad * 2f
            val thumbY = pad + usableH * (1f - fraction.coerceIn(0f, 1f))

            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(cx - trackW / 2f, pad),
                size = Size(trackW, usableH),
                cornerRadius = CornerRadius(trackW / 2f)
            )
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(tokens.accentColor, tokens.secondaryAccent)),
                topLeft = Offset(cx - trackW / 2f, thumbY),
                size = Size(trackW, (size.height - pad) - thumbY),
                cornerRadius = CornerRadius(trackW / 2f)
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, tokens.accentColor)),
                radius = 10.dp.toPx(),
                center = Offset(cx, thumbY)
            )
            drawCircle(
                color = Color.White,
                radius = 10.dp.toPx(),
                center = Offset(cx, thumbY),
                style = Stroke(width = 1.4.dp.toPx())
            )
        }
        Text(
            text = freqLabel,
            color = tokens.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
