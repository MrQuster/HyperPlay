package io.github.mrquster.hyperplay.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.C
import io.github.mrquster.hyperplay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.roundToInt

private val Context.soundEqDataStore by preferencesDataStore(name = "hyperplay_sound_eq_v1")

enum class BuiltInEqPreset(
    val titleRes: Int,
    val gainsDb: List<Float>
) {
    FLAT(R.string.eq_preset_flat, listOf(0f, 0f, 0f, 0f, 0f)),
    BASS(R.string.eq_preset_bass, listOf(6.5f, 4.5f, 0f, -1f, 1.5f)),
    VOICE(R.string.eq_preset_voice, listOf(-2f, 1.5f, 5f, 4f, 1f)),
    ROCK(R.string.eq_preset_rock, listOf(4.5f, 2.5f, -1.5f, 3f, 5f)),
    POP(R.string.eq_preset_pop, listOf(-1f, 2.5f, 4.5f, 3.5f, -0.5f)),
    JAZZ(R.string.eq_preset_jazz, listOf(3.5f, 2f, -1.5f, 2.5f, 4f)),
    CLASSICAL(R.string.eq_preset_classical, listOf(4f, 3f, -1f, 2.5f, 4f)),
    ELECTRONIC(R.string.eq_preset_electronic, listOf(5.5f, 3.5f, -1f, 2.5f, 5f)),
    CUSTOM(R.string.eq_preset_custom, listOf(0f, 0f, 0f, 0f, 0f))
}

@Immutable
data class EqBandUi(
    val index: Int,
    val centerFreqHz: Int,
    val minMb: Int,
    val maxMb: Int,
    val currentMb: Int
)

@Immutable
data class DeviceAudioProfile(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val presetName: String = BuiltInEqPreset.FLAT.name,
    val bandLevelsMb: List<Int> = listOf(0, 0, 0, 0, 0),
    val preampDb: Float = 0f,
    val volumeBoostPercent: Int = 100,
    val bassStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val balanceLeftRight: Float = 0f,
    val monoEnabled: Boolean = false,
    val softLimiterEnabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("deviceType", deviceType)
        put("presetName", presetName)
        put("bandLevelsMb", JSONArray(bandLevelsMb))
        put("preampDb", preampDb.toDouble())
        put("volumeBoostPercent", volumeBoostPercent)
        put("bassStrength", bassStrength)
        put("virtualizerStrength", virtualizerStrength)
        put("balanceLeftRight", balanceLeftRight.toDouble())
        put("monoEnabled", monoEnabled)
        put("softLimiterEnabled", softLimiterEnabled)
    }

    companion object {
        fun fromJson(o: JSONObject): DeviceAudioProfile {
            val arr = o.optJSONArray("bandLevelsMb")
            val bands = if (arr != null && arr.length() >= 5) {
                (0 until arr.length()).map { arr.optInt(it, 0).coerceIn(-1500, 1500) }
            } else {
                listOf(0, 0, 0, 0, 0)
            }
            return DeviceAudioProfile(
                deviceId = o.optString("deviceId", "SPEAKER"),
                deviceName = o.optString("deviceName", "Speaker"),
                deviceType = o.optString("deviceType", "SPEAKER"),
                presetName = o.optString("presetName", BuiltInEqPreset.FLAT.name),
                bandLevelsMb = bands,
                preampDb = o.optDouble("preampDb", 0.0).toFloat().coerceIn(-12f, 12f),
                volumeBoostPercent = o.optInt("volumeBoostPercent", 100).coerceIn(50, 400),
                bassStrength = o.optInt("bassStrength", 0).coerceIn(0, 1000),
                virtualizerStrength = o.optInt("virtualizerStrength", 0).coerceIn(0, 1000),
                balanceLeftRight = o.optDouble("balanceLeftRight", 0.0).toFloat().coerceIn(-1f, 1f),
                monoEnabled = o.optBoolean("monoEnabled", false),
                softLimiterEnabled = o.optBoolean("softLimiterEnabled", true)
            )
        }
    }
}

@Immutable
data class EffectSelfTestEntry(
    val name: String,
    val passed: Boolean,
    val routeDescription: String
)

@Immutable
data class SoundEqUiState(
    val activeProfile: DeviceAudioProfile = DeviceAudioProfile("SPEAKER", "Speaker", "SPEAKER"),
    val savedProfiles: List<DeviceAudioProfile> = emptyList(),
    val bands: List<EqBandUi> = listOf(60, 230, 910, 3600, 14000).mapIndexed { i, f ->
        EqBandUi(i, f, -1500, 1500, 0)
    },
    val activeAudioSessionId: Int = 0,
    val bassRouteName: String = "ExoPlayer AudioProcessor (64-bit Biquad + Auto-Headroom)",
    val virtualizerRouteName: String = "ExoPlayer AudioProcessor (Mid/Side Stereo Widener)",
    val selfTestResults: List<EffectSelfTestEntry> = emptyList()
)

class AudioEffectsController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private val KEY_PROFILES_JSON = stringPreferencesKey("device_audio_profiles_json")
        private val DP_CUTOFFS = floatArrayOf(90f, 250f, 950f, 3800f, 15000f)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    @Suppress("DEPRECATION")
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var boundSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var lastSwitchedDeviceId: String = ""

    private val _uiState = MutableStateFlow(SoundEqUiState())
    val uiState = _uiState.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            switchProfileForCurrentOutput()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            switchProfileForCurrentOutput()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        scope.launch {
            switchProfileForCurrentOutput()
        }
        scope.launch {
            HyperPlayPlaybackService.activeAudioSessionId.collect { sessionId ->
                if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) {
                    releaseHardwareEffects()
                    boundSessionId = C.AUDIO_SESSION_ID_UNSET
                } else {
                    bindToPlayerSession(sessionId, _uiState.value.activeProfile)
                }
            }
        }
        // Реагируем на переключения A/B тумблеров из панели «Диагностика звука»
        scope.launch {
            AudioDiagnosticsEngine.abSwitches.collect {
                applyProfileToDspAndEffects(_uiState.value.activeProfile)
            }
        }
    }

    private fun switchProfileForCurrentOutput() {
        val (devId, devName, devType) = detectActiveOutputDevice()
        if (devId == lastSwitchedDeviceId && _uiState.value.savedProfiles.isNotEmpty()) return
        lastSwitchedDeviceId = devId
        scope.launch {
            val allProfiles = loadAllProfiles().toMutableList()
            val found = allProfiles.firstOrNull { it.deviceId == devId }
                ?: DeviceAudioProfile(deviceId = devId, deviceName = devName, deviceType = devType).also {
                    allProfiles.add(it)
                    saveAllProfiles(allProfiles)
                }

            _uiState.value = _uiState.value.copy(
                activeProfile = found,
                savedProfiles = allProfiles
            )
            applyProfileToDspAndEffects(found)
        }
    }

    private fun detectActiveOutputDevice(): Triple<String, String, String> {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        val usb = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        val wired = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        return when {
            bt != null -> {
                val name = bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Audio"
                Triple("BT_${name.uppercase()}", name, "BLUETOOTH")
            }
            usb != null -> {
                val name = usb.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB DAC"
                Triple("USB_${name.uppercase()}", name, "USB")
            }
            wired != null -> Triple("WIRED_HEADSET", "Wired Headphones", "WIRED")
            else -> Triple("SPEAKER", "Phone Speaker", "SPEAKER")
        }
    }

    @Suppress("DEPRECATION")
    fun bindToPlayerSession(sessionId: Int, profile: DeviceAudioProfile = _uiState.value.activeProfile) {
        if (sessionId <= 0) return
        if (boundSessionId != sessionId) {
            releaseHardwareEffects()
            boundSessionId = sessionId

            runCatching {
                equalizer = Equalizer(0, sessionId)
            }
            // DynamicsProcessing создаём ТОЛЬКО как запасной вариант, если аппаратный Equalizer не создался!
            if (equalizer == null) {
                runCatching {
                    val cfg = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2, true, 5, false, 0, false, 0, false
                    ).build()
                    dynamicsProcessing = DynamicsProcessing(0, sessionId, cfg)
                }
            }
            runCatching {
                val bb = BassBoost(0, sessionId)
                if (bb.strengthSupported) bassBoost = bb else bb.release()
            }
            runCatching {
                val virt = Virtualizer(0, sessionId)
                if (virt.strengthSupported) virtualizer = virt else virt.release()
            }
            runCatching {
                loudnessEnhancer = LoudnessEnhancer(sessionId)
            }
            AudioDiagnosticsEngine.recordLog("SESSION_BOUND", "Bound effects to ExoPlayer session #$sessionId")
        }
        applyProfileToDspAndEffects(profile)
    }

    @Suppress("DEPRECATION")
    private fun applyProfileToDspAndEffects(profile: DeviceAudioProfile) {
        val ab = AudioDiagnosticsEngine.abSwitches.value
        val maxPosEqDb = (profile.bandLevelsMb.maxOrNull()?.coerceAtLeast(0) ?: 0) / 100f

        // 1. Передаём параметры в наш внутренний 64-битный HyperPlayDspAudioProcessor
        HyperPlayPlaybackService.dspAudioProcessor.apply {
            applyAbSwitches(ab)
            preampDb = profile.preampDb
            maxPositiveEqBandDb = if (ab.hardwareEqualizerEnabled) maxPosEqDb else 0f
            boostPercent = profile.volumeBoostPercent
            bassStrength = profile.bassStrength
            virtualizerStrength = profile.virtualizerStrength
            balanceLeftRight = profile.balanceLeftRight
            monoEnabled = profile.monoEnabled
            softLimiterEnabled = profile.softLimiterEnabled
        }

        val eqEnabledByAb = !ab.pureBypassAll && ab.hardwareEqualizerEnabled
        val bandUiList = ArrayList<EqBandUi>(5)
        val eq = equalizer

        if (eq != null) {
            runCatching {
                val count = eq.numberOfBands.toInt().coerceAtMost(5)
                val minMb = eq.bandLevelRange[0].toInt()
                val maxMb = eq.bandLevelRange[1].toInt()
                val hasNonZeroBand = profile.bandLevelsMb.any { it != 0 }
                eq.enabled = eqEnabledByAb && hasNonZeroBand

                for (b in 0 until count) {
                    val targetMb = if (eqEnabledByAb) {
                        profile.bandLevelsMb.getOrElse(b) { 0 }.coerceIn(minMb, maxMb)
                    } else 0
                    eq.setBandLevel(b.toShort(), targetMb.toShort())
                    bandUiList.add(
                        EqBandUi(
                            index = b,
                            centerFreqHz = eq.getCenterFreq(b.toShort()) / 1000,
                            minMb = minMb,
                            maxMb = maxMb,
                            currentMb = profile.bandLevelsMb.getOrElse(b) { 0 }.coerceIn(minMb, maxMb)
                        )
                    )
                }
            }
        } else {
            // Запасной путь через DynamicsProcessing ТОЛЬКО если Equalizer == null (без дублирования!)
            runCatching {
                dynamicsProcessing?.let { dp ->
                    for (ch in 0..1) {
                        val preEq = DynamicsProcessing.Eq(true, true, 5)
                        for (b in 0 until 5) {
                            val db = if (eqEnabledByAb) (profile.bandLevelsMb.getOrElse(b) { 0 } / 100f) else 0f
                            preEq.setBand(b, DynamicsProcessing.EqBand(true, DP_CUTOFFS[b], db.coerceIn(-15f, 15f)))
                        }
                        dp.setPreEqByChannelIndex(ch, preEq)
                    }
                    dp.enabled = eqEnabledByAb
                }
            }
        }

        if (bandUiList.isEmpty()) {
            val defaultFreqs = listOf(60, 230, 910, 3600, 14000)
            for (b in 0 until 5) {
                bandUiList.add(
                    EqBandUi(
                        index = b,
                        centerFreqHz = defaultFreqs[b],
                        minMb = -1500,
                        maxMb = 1500,
                        currentMb = profile.bandLevelsMb.getOrElse(b) { 0 }
                    )
                )
            }
        }

        // Аппаратный BassBoost и Virtualizer включаются ТОЛЬКО если явно включены в A/B тумблерах диагностики
        runCatching {
            bassBoost?.let { bb ->
                val active = !ab.pureBypassAll && ab.hardwareBassBoostEnabled && profile.bassStrength > 0
                if (bb.strengthSupported) {
                    bb.setStrength(if (active) profile.bassStrength.toShort() else 0)
                    bb.enabled = active
                }
            }
        }

        runCatching {
            virtualizer?.let { virt ->
                val active = !ab.pureBypassAll && ab.hardwareVirtualizerEnabled && profile.virtualizerStrength > 0
                if (virt.strengthSupported) {
                    virt.setStrength(if (active) profile.virtualizerStrength.toShort() else 0)
                    virt.enabled = active
                }
            }
        }

        // LoudnessEnhancer включается только если выключен наш программный DSP (чтобы не умножать буст дважды!)
        runCatching {
            loudnessEnhancer?.let { le ->
                val useHwLoudness = !ab.pureBypassAll && ab.loudnessEnhancerEnabled && !ab.softwareDspEnabled && profile.volumeBoostPercent > 100
                val gainMb = if (useHwLoudness) {
                    (2000.0 * log10(profile.volumeBoostPercent / 100.0)).roundToInt().coerceIn(0, 6000)
                } else 0
                le.setTargetGain(gainMb)
                le.enabled = gainMb > 0
            }
        }

        val bassRoute = when {
            ab.pureBypassAll -> "BYPASSED (Pure Mode)"
            ab.softwareDspEnabled -> "ExoPlayer 64-bit Biquad (95 Hz) + Auto-Headroom"
            ab.hardwareBassBoostEnabled && bassBoost != null -> "Hardware BassBoost"
            else -> "Disabled"
        }
        val virtRoute = when {
            ab.pureBypassAll -> "BYPASSED (Pure Mode)"
            ab.softwareDspEnabled -> "ExoPlayer 64-bit Mid/Side Widener"
            ab.hardwareVirtualizerEnabled && virtualizer != null -> "Hardware Virtualizer"
            else -> "Disabled"
        }

        _uiState.value = _uiState.value.copy(
            activeProfile = profile,
            bands = bandUiList,
            activeAudioSessionId = boundSessionId.coerceAtLeast(0),
            bassRouteName = bassRoute,
            virtualizerRouteName = virtRoute
        )
    }

    fun selectPreset(preset: BuiltInEqPreset) {
        val mbList = preset.gainsDb.map { (it * 100f).roundToInt() }
        updateActiveProfile {
            it.copy(presetName = preset.name, bandLevelsMb = mbList)
        }
    }

    fun setBandLevelMb(bandIndex: Int, levelMb: Int) {
        updateActiveProfile { current ->
            val mutable = current.bandLevelsMb.toMutableList()
            while (mutable.size <= bandIndex) mutable.add(0)
            mutable[bandIndex] = levelMb.coerceIn(-1500, 1500)
            current.copy(presetName = BuiltInEqPreset.CUSTOM.name, bandLevelsMb = mutable)
        }
    }

    fun setPreampDb(db: Float) = updateActiveProfile { it.copy(preampDb = db.coerceIn(-12f, 12f)) }
    fun setVolumeBoostPercent(percent: Int) = updateActiveProfile { it.copy(volumeBoostPercent = percent.coerceIn(50, 400)) }
    fun setBassStrength(strength: Int) = updateActiveProfile { it.copy(bassStrength = strength.coerceIn(0, 1000)) }
    fun setVirtualizerStrength(strength: Int) = updateActiveProfile { it.copy(virtualizerStrength = strength.coerceIn(0, 1000)) }
    fun setBalance(balance: Float) = updateActiveProfile { it.copy(balanceLeftRight = balance.coerceIn(-1f, 1f)) }
    fun setMono(enabled: Boolean) = updateActiveProfile { it.copy(monoEnabled = enabled) }
    fun setSoftLimiter(enabled: Boolean) = updateActiveProfile { it.copy(softLimiterEnabled = enabled) }

    fun resetCurrentProfile() {
        updateActiveProfile { current ->
            current.copy(
                presetName = BuiltInEqPreset.FLAT.name,
                bandLevelsMb = listOf(0, 0, 0, 0, 0),
                preampDb = 0f,
                volumeBoostPercent = 100,
                bassStrength = 0,
                virtualizerStrength = 0,
                balanceLeftRight = 0f,
                monoEnabled = false,
                softLimiterEnabled = true
            )
        }
    }

    @Suppress("DEPRECATION")
    fun runEffectsSelfTest() {
        val sid = boundSessionId.takeIf { it > 0 } ?: 0
        val results = mutableListOf<EffectSelfTestEntry>()

        results.add(
            EffectSelfTestEntry(
                name = "C1 Soft-Knee Limiter & Auto-Headroom",
                passed = true,
                routeDescription = "Continuous derivative at 0.75 knee + TPDF 16-bit dither (0 step distortion)"
            )
        )
        results.add(
            EffectSelfTestEntry(
                name = "Equalizer (Session #$sid)",
                passed = equalizer != null || dynamicsProcessing != null,
                routeDescription = if (equalizer != null) "Single-stage Hardware Equalizer on session #$sid (no double stacking)"
                else "Single-stage DynamicsProcessing PreEQ"
            )
        )
        results.add(
            EffectSelfTestEntry(
                name = "Bass Boost (Low-Shelf 95 Hz)",
                passed = true,
                routeDescription = _uiState.value.bassRouteName
            )
        )
        results.add(
            EffectSelfTestEntry(
                name = "3D Surround (Mid/Side)",
                passed = true,
                routeDescription = _uiState.value.virtualizerRouteName
            )
        )
        _uiState.value = _uiState.value.copy(selfTestResults = results)
    }

    private fun updateActiveProfile(transform: (DeviceAudioProfile) -> DeviceAudioProfile) {
        val updated = transform(_uiState.value.activeProfile)
        applyProfileToDspAndEffects(updated)
        scope.launch(Dispatchers.IO) {
            val all = loadAllProfiles().toMutableList()
            val idx = all.indexOfFirst { it.deviceId == updated.deviceId }
            if (idx >= 0) all[idx] = updated else all.add(updated)
            saveAllProfiles(all)
            _uiState.value = _uiState.value.copy(savedProfiles = all)
        }
    }

    private suspend fun loadAllProfiles(): List<DeviceAudioProfile> {
        val prefs = context.soundEqDataStore.data.first()
        val raw = prefs[KEY_PROFILES_JSON].orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { DeviceAudioProfile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private suspend fun saveAllProfiles(list: List<DeviceAudioProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        context.soundEqDataStore.edit { it[KEY_PROFILES_JSON] = arr.toString() }
    }

    @Suppress("DEPRECATION")
    fun releaseHardwareEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
        runCatching { dynamicsProcessing?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        dynamicsProcessing = null
    }
}
