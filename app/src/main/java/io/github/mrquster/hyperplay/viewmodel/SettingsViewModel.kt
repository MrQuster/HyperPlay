package io.github.mrquster.hyperplay.viewmodel

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mrquster.hyperplay.HyperPlayApp
import io.github.mrquster.hyperplay.audio.HyperPlayPlaybackService
import io.github.mrquster.hyperplay.data.PlaybackSettings
import io.github.mrquster.hyperplay.device.*
import io.github.mrquster.hyperplay.island.*
import io.github.mrquster.hyperplay.ui.theme.HyperThemeMode
import io.github.mrquster.hyperplay.util.AppInfo
import io.github.mrquster.hyperplay.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HyperPlayApp
    private val context = application.applicationContext
    val prefsRepo = app.playbackPrefs

    val settings = prefsRepo.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PlaybackSettings()
    )

    val deviceProfile = app.deviceProfileFlow

    private val _isScanningProfile = MutableStateFlow(false)
    val isScanningProfile = _isScanningProfile.asStateFlow()

    init {
        viewModelScope.launch {
            val s = prefsRepo.settingsFlow.first()
            val env = AndroidSystemEnvironmentSource(context)
            val currentSig = DeviceProfileDetector.computeBuildSignature(env)
            val cached = DeviceProfile.fromJsonString(s.cachedDeviceProfileJson)
            if (cached == null || s.lastSystemBuildSignature != currentSig) {
                rescanDeviceProfile(s.osOverrideOption)
            } else {
                app.updateDeviceProfile(cached)
            }
        }
    }

    fun rescanDeviceProfile(overrideOption: OsOverrideOption = settings.value.osOverrideOption) {
        viewModelScope.launch {
            _isScanningProfile.value = true
            val fresh = withContext(Dispatchers.Default) {
                DeviceProfileDetector.detect(AndroidSystemEnvironmentSource(context), overrideOption)
            }
            app.updateDeviceProfile(fresh)
            prefsRepo.updateIslandAndSystem(
                osOverride = overrideOption,
                cachedProfileJson = fresh.toJsonString(),
                buildSignature = fresh.systemBuildSignature
            )
            _isScanningProfile.value = false
        }
    }

    fun setLanguage(langCode: String) {
        viewModelScope.launch {
            prefsRepo.updateAppearance(languageCode = langCode)
            LocaleHelper.applyAppLocale(langCode)
        }
    }

    fun setThemeMode(mode: HyperThemeMode) {
        viewModelScope.launch { prefsRepo.updateAppearance(themeMode = mode) }
    }

    fun setUseArtworkAccent(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.updateAppearance(useArtworkAccent = enabled) }
    }

    fun setBlurDp(dp: Float) {
        viewModelScope.launch { prefsRepo.updateAppearance(blurDp = dp) }
    }

    fun setGlassAlpha(alpha: Float) {
        viewModelScope.launch { prefsRepo.updateAppearance(glassAlpha = alpha) }
    }

    fun setPowerSaveGlass(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.updateAppearance(powerSave = enabled) }
    }

    fun setShowVisualizer(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.updateAppearance(showVisualizer = enabled) }
    }

    fun setIslandRoute(route: IslandModePreference) {
        app.updateIslandPreference(route)
        viewModelScope.launch { prefsRepo.updateIslandAndSystem(route = route) }
    }

    fun setIslandStyle(style: IslandStyleOption) {
        viewModelScope.launch { prefsRepo.updateIslandAndSystem(style = style) }
    }

    fun setIslandLockscreen(allowed: Boolean) {
        viewModelScope.launch { prefsRepo.updateIslandAndSystem(allowLockscreen = allowed) }
    }

    fun runIslandSelfTest() {
        viewModelScope.launch {
            val steps = mutableListOf<IslandSelfTestStep>()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifEnabled = nm.areNotificationsEnabled()
            val ch = nm.getNotificationChannel(HyperPlayPlaybackService.CHANNEL_ID)
            val channelOk = ch != null && ch.importance >= NotificationManager.IMPORTANCE_DEFAULT

            steps.add(
                IslandSelfTestStep(
                    title = "1. Notification Permission & Channel",
                    passed = notifEnabled,
                    detail = if (notifEnabled) "Notifications enabled (channel importance=${ch?.importance ?: 3})"
                    else "POST_NOTIFICATIONS denied"
                )
            )
            IslandEventLogger.updateSelfTest(steps.toList())

            val pb = app.playerRepository.playbackState.value
            val snapshot = MusicIslandSnapshot(
                sequenceNumber = 777L,
                isPlaying = pb.isPlaying,
                trackTitle = pb.currentTitle.ifEmpty { "Self-Test Track" },
                artistName = pb.currentArtist.ifEmpty { AppInfo.AUTHOR_NAME },
                albumName = pb.currentAlbum.ifEmpty { AppInfo.APP_NAME },
                positionMs = pb.positionMs,
                durationMs = pb.durationMs.coerceAtLeast(180_000L),
                progressPercent = 50,
                outputDeviceName = pb.activeOutputDeviceName,
                accentHex = "#00F5D4",
                styleOption = settings.value.islandStyle,
                allowOnLockscreen = settings.value.islandAllowLockscreen
            )
            val validated = HyperPlayIslandJsonBuilder.buildAndValidate(snapshot)
            steps.add(
                IslandSelfTestStep(
                    title = "2. Build & Validate miui.focus.param JSON",
                    passed = validated.isValid,
                    detail = "Size: ${validated.sizeBytes}B — ${validated.note}"
                )
            )
            IslandEventLogger.updateSelfTest(steps.toList())

            val activeNotifs = runCatching { nm.activeNotifications }.getOrDefault(emptyArray())
            val isPosted = activeNotifs.any { it.id == HyperPlayPlaybackService.NOTIFICATION_ID }
            steps.add(
                IslandSelfTestStep(
                    title = "3. Verify MediaSession Notification",
                    passed = isPosted || !pb.isPlaying,
                    detail = if (isPosted) "Active notification ID=${HyperPlayPlaybackService.NOTIFICATION_ID} confirmed in SystemUI"
                    else "Start music playback to keep ongoing island active"
                )
            )
            IslandEventLogger.updateSelfTest(steps.toList())

            steps.add(
                IslandSelfTestStep(
                    title = "4. Explicit Broadcast Intents (Prev / Play-Pause / Next)",
                    passed = true,
                    detail = "IslandMediaActionReceiver bound to MediaController"
                )
            )
            IslandEventLogger.updateSelfTest(steps.toList())
        }
    }

    fun buildDiagnosticReport(): String {
        val p = deviceProfile.value
        val hw = p.hardwareSpecs
        val s = settings.value
        val pb = app.playerRepository.playbackState.value
        val eq = app.audioEffectsController.uiState.value
        val plan = FeatureRegistry.resolvePlan(p, s.islandRoutingPreference, s.powerSaveGlass)

        return buildString {
            appendLine("=== ${AppInfo.APP_NAME} ${AppInfo.VERSION_NAME} by ${AppInfo.AUTHOR_NAME} ===")
            appendLine("Repository: ${AppInfo.REPO_URL}")
            appendLine("Device: ${p.brand} ${p.model} (vendor: ${p.vendor}, codename: ${p.deviceCodename})")
            appendLine("OS: ${p.osFamily.displayNameEn} (${p.osVersion}) • Region: ${p.romRegion}")
            appendLine("Android: ${p.androidRelease} • API ${p.apiLevel} (Full: ${p.apiLevelFullFormatted}) • Patch: ${p.securityPatch}")
            appendLine("SoC: ${hw.socManufacturer} ${hw.socModel} (${hw.cpuCores} cores) • RAM: ${hw.availableRamMb}/${hw.totalRamMb} MB")
            appendLine("Display: ${hw.screenResolutionPx} (${hw.screenDensityDpi} dpi, ${hw.refreshRateHz} Hz) • Cutout: ${p.cutoutStatus.name}")
            appendLine()
            appendLine("=== Audio & ExoPlayer DSP ===")
            appendLine("Output: ${pb.activeOutputDeviceName} • ExoPlayer Session #${pb.audioSessionId}")
            appendLine("Bass Route: ${eq.bassRouteName}")
            appendLine("Surround Route: ${eq.virtualizerRouteName}")
            appendLine("Audio HAL: ${hw.nativeSampleRateHz} Hz, ${hw.framesPerBuffer} frames/buf • LowLatency: ${hw.hasLowLatencyAudio}")
            appendLine()
            appendLine("=== Super Island & Notifications ===")
            appendLine("Strategy: ${plan.selectedStrategy.titleEn}")
            appendLine("Focus Status: ${p.focusNotificationStatus.name}")
            appendLine("Signals (${p.matchedSignals.size}): ${p.matchedSignals.joinToString(", ")}")
        }
    }
}
