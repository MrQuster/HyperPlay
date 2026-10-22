package io.github.mrquster.hyperplay.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import io.github.mrquster.hyperplay.device.IslandModePreference
import io.github.mrquster.hyperplay.device.IslandStyleOption
import io.github.mrquster.hyperplay.device.OsOverrideOption
import io.github.mrquster.hyperplay.ui.theme.HyperThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.playbackDataStore by preferencesDataStore(name = "hyperplay_playback_prefs_v1")

enum class SortOption(val key: String) {
    TITLE_ASC("TITLE_ASC"),
    ARTIST_ASC("ARTIST_ASC"),
    ALBUM_ASC("ALBUM_ASC"),
    DATE_ADDED_DESC("DATE_ADDED_DESC"),
    DURATION_DESC("DURATION_DESC"),
    YEAR_DESC("YEAR_DESC")
}

@Immutable
data class PlaybackSettings(
    val onboardingCompleted: Boolean = false,
    val minTrackDurationSec: Int = 10,
    val hideMessagingAndRingtoneFolders: Boolean = true,
    val excludedFolderPaths: Set<String> = emptySet(),
    val sortOption: SortOption = SortOption.TITLE_ASC,
    val gaplessEnabled: Boolean = true,
    val crossfadeDurationSec: Int = 0,
    val replayGainEnabled: Boolean = false,
    val skipSilenceEnabled: Boolean = false,
    val autoResumeOnBluetooth: Boolean = false,
    val rememberQueueAndPosition: Boolean = true,
    // Оформление и Liquid Glass
    val themeMode: HyperThemeMode = HyperThemeMode.DARK,
    val useArtworkAccent: Boolean = true,
    val blurIntensityDp: Float = 32f,
    val glassAlpha: Float = 0.20f,
    val powerSaveGlass: Boolean = false,
    val showVisualizer: Boolean = true,
    val languageCode: String = "system",
    // Остров и система
    val islandRoutingPreference: IslandModePreference = IslandModePreference.AUTO,
    val islandStyle: IslandStyleOption = IslandStyleOption.COMPACT,
    val islandAllowLockscreen: Boolean = true,
    val osOverrideOption: OsOverrideOption = OsOverrideOption.AUTO,
    val cachedDeviceProfileJson: String = "",
    val lastSystemBuildSignature: String = ""
)

@Immutable
data class SavedQueueSnapshot(
    val trackIds: List<Long> = emptyList(),
    val currentTrackId: Long = -1L,
    val currentPositionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0
)

class PlaybackPreferencesRepository(private val context: Context) {

    companion object {
        private val KEY_ONBOARDING = booleanPreferencesKey("onboarding_completed")
        private val KEY_MIN_DURATION_SEC = intPreferencesKey("min_track_duration_sec")
        private val KEY_HIDE_SYSTEM_FOLDERS = booleanPreferencesKey("hide_messaging_ringtone_folders")
        private val KEY_EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folder_paths")
        private val KEY_SORT_OPTION = stringPreferencesKey("sort_option")
        private val KEY_GAPLESS = booleanPreferencesKey("gapless_enabled")
        private val KEY_CROSSFADE_SEC = intPreferencesKey("crossfade_duration_sec")
        private val KEY_REPLAY_GAIN = booleanPreferencesKey("replay_gain_enabled")
        private val KEY_SKIP_SILENCE = booleanPreferencesKey("skip_silence_enabled")
        private val KEY_AUTO_BT = booleanPreferencesKey("auto_resume_bluetooth")
        private val KEY_REMEMBER_QUEUE = booleanPreferencesKey("remember_queue_position")

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_ARTWORK_ACCENT = booleanPreferencesKey("use_artwork_accent")
        private val KEY_BLUR_DP = floatPreferencesKey("blur_intensity_dp")
        private val KEY_GLASS_ALPHA = floatPreferencesKey("glass_alpha")
        private val KEY_POWER_SAVE_GLASS = booleanPreferencesKey("power_save_glass")
        private val KEY_SHOW_VISUALIZER = booleanPreferencesKey("show_visualizer")
        private val KEY_LANGUAGE = stringPreferencesKey("language_code")

        private val KEY_ISLAND_ROUTE = stringPreferencesKey("island_route_pref")
        private val KEY_ISLAND_STYLE = stringPreferencesKey("island_style_opt")
        private val KEY_ISLAND_LOCKSCREEN = booleanPreferencesKey("island_allow_lockscreen")
        private val KEY_OS_OVERRIDE = stringPreferencesKey("os_override_option")
        private val KEY_CACHED_PROFILE_JSON = stringPreferencesKey("cached_device_profile_json")
        private val KEY_BUILD_SIGNATURE = stringPreferencesKey("last_system_build_signature")

        private val KEY_SAVED_QUEUE_CSV = stringPreferencesKey("saved_queue_ids_csv")
        private val KEY_SAVED_CURRENT_ID = longPreferencesKey("saved_current_track_id")
        private val KEY_SAVED_POSITION_MS = longPreferencesKey("saved_position_ms")
        private val KEY_SAVED_SHUFFLE = booleanPreferencesKey("saved_shuffle")
        private val KEY_SAVED_REPEAT = intPreferencesKey("saved_repeat_mode")
    }

    val settingsFlow: Flow<PlaybackSettings> = context.playbackDataStore.data.map { prefs ->
        val sortOpt = runCatching {
            SortOption.valueOf(prefs[KEY_SORT_OPTION] ?: SortOption.TITLE_ASC.name)
        }.getOrDefault(SortOption.TITLE_ASC)

        val themeOpt = runCatching {
            HyperThemeMode.valueOf(prefs[KEY_THEME_MODE] ?: HyperThemeMode.DARK.name)
        }.getOrDefault(HyperThemeMode.DARK)

        val islandRoute = runCatching {
            IslandModePreference.valueOf(prefs[KEY_ISLAND_ROUTE] ?: IslandModePreference.AUTO.name)
        }.getOrDefault(IslandModePreference.AUTO)

        val islandStyle = runCatching {
            IslandStyleOption.valueOf(prefs[KEY_ISLAND_STYLE] ?: IslandStyleOption.COMPACT.name)
        }.getOrDefault(IslandStyleOption.COMPACT)

        val osOverride = runCatching {
            OsOverrideOption.valueOf(prefs[KEY_OS_OVERRIDE] ?: OsOverrideOption.AUTO.name)
        }.getOrDefault(OsOverrideOption.AUTO)

        PlaybackSettings(
            onboardingCompleted = prefs[KEY_ONBOARDING] ?: false,
            minTrackDurationSec = (prefs[KEY_MIN_DURATION_SEC] ?: 10).coerceIn(0, 600),
            hideMessagingAndRingtoneFolders = prefs[KEY_HIDE_SYSTEM_FOLDERS] ?: true,
            excludedFolderPaths = prefs[KEY_EXCLUDED_FOLDERS] ?: emptySet(),
            sortOption = sortOpt,
            gaplessEnabled = prefs[KEY_GAPLESS] ?: true,
            crossfadeDurationSec = (prefs[KEY_CROSSFADE_SEC] ?: 0).coerceIn(0, 12),
            replayGainEnabled = prefs[KEY_REPLAY_GAIN] ?: false,
            skipSilenceEnabled = prefs[KEY_SKIP_SILENCE] ?: false,
            autoResumeOnBluetooth = prefs[KEY_AUTO_BT] ?: false,
            rememberQueueAndPosition = prefs[KEY_REMEMBER_QUEUE] ?: true,
            themeMode = themeOpt,
            useArtworkAccent = prefs[KEY_ARTWORK_ACCENT] ?: true,
            blurIntensityDp = (prefs[KEY_BLUR_DP] ?: 32f).coerceIn(16f, 64f),
            glassAlpha = (prefs[KEY_GLASS_ALPHA] ?: 0.20f).coerceIn(0.08f, 0.40f),
            powerSaveGlass = prefs[KEY_POWER_SAVE_GLASS] ?: false,
            showVisualizer = prefs[KEY_SHOW_VISUALIZER] ?: true,
            languageCode = prefs[KEY_LANGUAGE] ?: "system",
            islandRoutingPreference = islandRoute,
            islandStyle = islandStyle,
            islandAllowLockscreen = prefs[KEY_ISLAND_LOCKSCREEN] ?: true,
            osOverrideOption = osOverride,
            cachedDeviceProfileJson = prefs[KEY_CACHED_PROFILE_JSON].orEmpty(),
            lastSystemBuildSignature = prefs[KEY_BUILD_SIGNATURE].orEmpty()
        )
    }

    suspend fun updateSortOption(option: SortOption) {
        context.playbackDataStore.edit { it[KEY_SORT_OPTION] = option.name }
    }

    suspend fun updateMinDurationSec(seconds: Int) {
        context.playbackDataStore.edit { it[KEY_MIN_DURATION_SEC] = seconds.coerceIn(0, 600) }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.playbackDataStore.edit { it[KEY_ONBOARDING] = completed }
    }

    suspend fun updatePlaybackFlags(
        gapless: Boolean? = null,
        crossfadeSec: Int? = null,
        replayGain: Boolean? = null,
        skipSilence: Boolean? = null,
        autoBluetooth: Boolean? = null,
        rememberQueue: Boolean? = null,
        hideSystemFolders: Boolean? = null
    ) {
        context.playbackDataStore.edit { prefs ->
            gapless?.let { prefs[KEY_GAPLESS] = it }
            crossfadeSec?.let { prefs[KEY_CROSSFADE_SEC] = it.coerceIn(0, 12) }
            replayGain?.let { prefs[KEY_REPLAY_GAIN] = it }
            skipSilence?.let { prefs[KEY_SKIP_SILENCE] = it }
            autoBluetooth?.let { prefs[KEY_AUTO_BT] = it }
            rememberQueue?.let { prefs[KEY_REMEMBER_QUEUE] = it }
            hideSystemFolders?.let { prefs[KEY_HIDE_SYSTEM_FOLDERS] = it }
        }
    }

    suspend fun updateAppearance(
        themeMode: HyperThemeMode? = null,
        useArtworkAccent: Boolean? = null,
        blurDp: Float? = null,
        glassAlpha: Float? = null,
        powerSave: Boolean? = null,
        showVisualizer: Boolean? = null,
        languageCode: String? = null
    ) {
        context.playbackDataStore.edit { prefs ->
            themeMode?.let { prefs[KEY_THEME_MODE] = it.name }
            useArtworkAccent?.let { prefs[KEY_ARTWORK_ACCENT] = it }
            blurDp?.let { prefs[KEY_BLUR_DP] = it.coerceIn(16f, 64f) }
            glassAlpha?.let { prefs[KEY_GLASS_ALPHA] = it.coerceIn(0.08f, 0.40f) }
            powerSave?.let { prefs[KEY_POWER_SAVE_GLASS] = it }
            showVisualizer?.let { prefs[KEY_SHOW_VISUALIZER] = it }
            languageCode?.let { prefs[KEY_LANGUAGE] = it }
        }
    }

    suspend fun updateIslandAndSystem(
        route: IslandModePreference? = null,
        style: IslandStyleOption? = null,
        allowLockscreen: Boolean? = null,
        osOverride: OsOverrideOption? = null,
        cachedProfileJson: String? = null,
        buildSignature: String? = null
    ) {
        context.playbackDataStore.edit { prefs ->
            route?.let { prefs[KEY_ISLAND_ROUTE] = it.name }
            style?.let { prefs[KEY_ISLAND_STYLE] = it.name }
            allowLockscreen?.let { prefs[KEY_ISLAND_LOCKSCREEN] = it }
            osOverride?.let { prefs[KEY_OS_OVERRIDE] = it.name }
            cachedProfileJson?.let { prefs[KEY_CACHED_PROFILE_JSON] = it }
            buildSignature?.let { prefs[KEY_BUILD_SIGNATURE] = it }
        }
    }

    suspend fun resetAllSettings() {
        context.playbackDataStore.edit { prefs ->
            val onb = prefs[KEY_ONBOARDING] ?: true
            prefs.clear()
            prefs[KEY_ONBOARDING] = onb
        }
    }

    suspend fun exportSettingsJson(): String {
        val s = settingsFlow.first()
        return JSONObject().apply {
            put("app", "HyperPlay")
            put("version", 1)
            put("minTrackDurationSec", s.minTrackDurationSec)
            put("hideMessagingAndRingtoneFolders", s.hideMessagingAndRingtoneFolders)
            put("crossfadeDurationSec", s.crossfadeDurationSec)
            put("replayGainEnabled", s.replayGainEnabled)
            put("skipSilenceEnabled", s.skipSilenceEnabled)
            put("autoResumeOnBluetooth", s.autoResumeOnBluetooth)
            put("themeMode", s.themeMode.name)
            put("useArtworkAccent", s.useArtworkAccent)
            put("blurIntensityDp", s.blurIntensityDp.toDouble())
            put("glassAlpha", s.glassAlpha.toDouble())
            put("powerSaveGlass", s.powerSaveGlass)
            put("languageCode", s.languageCode)
            put("islandRoutingPreference", s.islandRoutingPreference.name)
            put("islandStyle", s.islandStyle.name)
        }.toString(2)
    }

    suspend fun importSettingsJson(rawJson: String): Boolean {
        return runCatching {
            val o = JSONObject(rawJson)
            if (o.optString("app") != "HyperPlay") return false
            updateMinDurationSec(o.optInt("minTrackDurationSec", 10))
            updatePlaybackFlags(
                crossfadeSec = o.optInt("crossfadeDurationSec", 0),
                replayGain = o.optBoolean("replayGainEnabled", false),
                skipSilence = o.optBoolean("skipSilenceEnabled", false),
                autoBluetooth = o.optBoolean("autoResumeOnBluetooth", false),
                hideSystemFolders = o.optBoolean("hideMessagingAndRingtoneFolders", true)
            )
            val theme = runCatching { HyperThemeMode.valueOf(o.optString("themeMode", "DARK")) }
                .getOrDefault(HyperThemeMode.DARK)
            updateAppearance(
                themeMode = theme,
                useArtworkAccent = o.optBoolean("useArtworkAccent", true),
                blurDp = o.optDouble("blurIntensityDp", 32.0).toFloat(),
                glassAlpha = o.optDouble("glassAlpha", 0.20).toFloat(),
                powerSave = o.optBoolean("powerSaveGlass", false),
                languageCode = o.optString("languageCode", "system")
            )
            true
        }.getOrDefault(false)
    }

    suspend fun savePositionOnlySnapshot(
        currentTrackId: Long,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int
    ) {
        context.playbackDataStore.edit { prefs ->
            prefs[KEY_SAVED_CURRENT_ID] = currentTrackId
            prefs[KEY_SAVED_POSITION_MS] = positionMs.coerceAtLeast(0L)
            prefs[KEY_SAVED_SHUFFLE] = shuffleEnabled
            prefs[KEY_SAVED_REPEAT] = repeatMode
        }
    }

    suspend fun saveQueueSnapshot(
        trackIds: List<Long>,
        currentTrackId: Long,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int
    ) {
        context.playbackDataStore.edit { prefs ->
            prefs[KEY_SAVED_QUEUE_CSV] = trackIds.take(2500).joinToString(",")
            prefs[KEY_SAVED_CURRENT_ID] = currentTrackId
            prefs[KEY_SAVED_POSITION_MS] = positionMs.coerceAtLeast(0L)
            prefs[KEY_SAVED_SHUFFLE] = shuffleEnabled
            prefs[KEY_SAVED_REPEAT] = repeatMode
        }
    }

    suspend fun loadSavedQueueSnapshot(): SavedQueueSnapshot {
        val prefs = context.playbackDataStore.data.first()
        val csv = prefs[KEY_SAVED_QUEUE_CSV].orEmpty()
        val ids = if (csv.isBlank()) emptyList() else csv.split(",").mapNotNull { it.toLongOrNull() }
        return SavedQueueSnapshot(
            trackIds = ids,
            currentTrackId = prefs[KEY_SAVED_CURRENT_ID] ?: -1L,
            currentPositionMs = prefs[KEY_SAVED_POSITION_MS] ?: 0L,
            shuffleEnabled = prefs[KEY_SAVED_SHUFFLE] ?: false,
            repeatMode = prefs[KEY_SAVED_REPEAT] ?: 0
        )
    }
}
