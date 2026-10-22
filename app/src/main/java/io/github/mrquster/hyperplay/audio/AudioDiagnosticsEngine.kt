package io.github.mrquster.hyperplay.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

@Immutable
data class AudioAbSwitches(
    val pureBypassAll: Boolean = false,
    val hardwareEqualizerEnabled: Boolean = true,
    val hardwareBassBoostEnabled: Boolean = false,
    val hardwareVirtualizerEnabled: Boolean = false,
    val loudnessEnhancerEnabled: Boolean = true,
    val softwareDspEnabled: Boolean = true,
    val autoHeadroomEnabled: Boolean = true,
    val dither16BitEnabled: Boolean = true,
    val replayGainEnabled: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val skipSilenceEnabled: Boolean = false,
    val speedChangeAllowed: Boolean = true,
    val pcmFloatOutputEnabled: Boolean = false,
    val offloadEnabled: Boolean = false,
    val tunnelingEnabled: Boolean = false,
    val pauseScannerDuringPlayback: Boolean = true,
    val islandUpdatesEnabled: Boolean = true
)

@Immutable
data class RealTimeAudioMetrics(
    val sampleRateHz: Int = 48000,
    val channelCount: Int = 2,
    val encodingName: String = "PCM_16BIT",
    val decoderName: String = "MediaCodec / ExoPlayer",
    val inputPeakDbFs: Float = -96f,
    val inputRmsDbFs: Float = -96f,
    val outputPeakDbFs: Float = -96f,
    val outputRmsDbFs: Float = -96f,
    val autoHeadroomAttenuationDb: Float = 0f,
    val limiterGainReductionDb: Float = 0f,
    val clippedSamplesCount: Long = 0L,
    val underrunCount: Int = 0,
    val audioBufferSizeMs: Int = 0,
    val estimatedAppCpuPercent: Float = 0f,
    val isTestTonePlaying: Boolean = false,
    val activeTestToneType: String = "NONE",
    val safeFallbackTriggered: Boolean = false
)

@Immutable
data class AudioDiagLogItem(
    val timestampMs: Long,
    val timeFormatted: String,
    val event: String,
    val detail: String
)

object AudioDiagnosticsEngine {

    const val LOG_TAG = "HyperPlay/Audio"
    private const val MAX_LOG_ITEMS = 200

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logLock = Any()
    private val logBuffer = ArrayDeque<AudioDiagLogItem>(MAX_LOG_ITEMS)

    private val _abSwitches = MutableStateFlow(AudioAbSwitches())
    val abSwitches = _abSwitches.asStateFlow()

    private val _metrics = MutableStateFlow(RealTimeAudioMetrics())
    val metrics = _metrics.asStateFlow()

    private val _logsFlow = MutableStateFlow<List<AudioDiagLogItem>>(emptyList())
    val logsFlow = _logsFlow.asStateFlow()

    @Volatile
    private var pendingSafeFallback = false

    private var testToneJob: Job? = null
    private var lastCpuTimeMs: Long = 0L
    private var lastWallTimeMs: Long = 0L

    init {
        startMetricsAggregatorLoop()
    }

    fun updateSwitches(transform: (AudioAbSwitches) -> AudioAbSwitches) {
        val prev = _abSwitches.value
        val next = transform(prev)
        if (next == prev) return
        _abSwitches.value = next
        HyperPlayPlaybackService.dspAudioProcessor.applyAbSwitches(next)
        recordLog(
            event = "AB_SWITCH_CHANGED",
            detail = "pureBypass=${next.pureBypassAll}, swDsp=${next.softwareDspEnabled}, " +
                    "autoHeadroom=${next.autoHeadroomEnabled}, hwEq=${next.hardwareEqualizerEnabled}, " +
                    "pauseScanner=${next.pauseScannerDuringPlayback}"
        )
    }

    fun togglePureBypassMode() {
        updateSwitches { current ->
            current.copy(pureBypassAll = !current.pureBypassAll)
        }
    }

    fun applyConservativeSafeMode() {
        pendingSafeFallback = false
        updateSwitches {
            it.copy(
                pureBypassAll = false,
                hardwareBassBoostEnabled = false,
                hardwareVirtualizerEnabled = false,
                softwareDspEnabled = true,
                autoHeadroomEnabled = true,
                dither16BitEnabled = true,
                pcmFloatOutputEnabled = false,
                offloadEnabled = false,
                tunnelingEnabled = false,
                skipSilenceEnabled = false,
                pauseScannerDuringPlayback = true
            )
        }
        _metrics.value = _metrics.value.copy(safeFallbackTriggered = true)
        recordLog("SAFE_MODE_APPLIED", "Switched to conservative PCM 16-bit + Auto-Headroom + C1 Limiter")
    }

    /**
     * Вызывается на границе смены трека (onMediaItemTransition), чтобы не прерывать играющий трек
     */
    fun applyPendingFallbackOnTrackBoundaryIfNeeded() {
        if (pendingSafeFallback) {
            pendingSafeFallback = false
            applyConservativeSafeMode()
        }
    }

    fun onAudioUnderrunOccurred(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        val newCount = _metrics.value.underrunCount + 1
        _metrics.value = _metrics.value.copy(
            underrunCount = newCount,
            audioBufferSizeMs = bufferSizeMs.toInt()
        )
        recordLog(
            event = "AUDIO_UNDERRUN (#$newCount)",
            detail = "buf=${bufferSize}B (${bufferSizeMs}ms), gap=${elapsedSinceLastFeedMs}ms"
        )
        // Планируем мягкий откат на границе следующего трека (без разрыва посередине песни!)
        if (newCount >= 3 && !_metrics.value.safeFallbackTriggered) {
            pendingSafeFallback = true
            recordLog("SAFE_MODE_SCHEDULED", "Scheduled safe fallback for next track boundary")
        }
    }

    fun onDecoderInitialized(decoderName: String) {
        _metrics.value = _metrics.value.copy(decoderName = decoderName)
        recordLog("DECODER_INIT", "Active decoder: $decoderName")
    }

    fun resetCounters() {
        HyperPlayPlaybackService.dspAudioProcessor.resetDiagnosticCounters()
        pendingSafeFallback = false
        _metrics.value = _metrics.value.copy(
            clippedSamplesCount = 0L,
            underrunCount = 0,
            safeFallbackTriggered = false
        )
        recordLog("COUNTERS_RESET", "Clipped and underrun counters cleared")
    }

    fun playReferenceSignal(isPinkNoise: Boolean) {
        if (testToneJob?.isActive == true) {
            stopReferenceSignal()
            return
        }
        val label = if (isPinkNoise) "PINK_NOISE_-12DBFS" else "SINE_1KHZ_-12DBFS"
        recordLog("TEST_SIGNAL_START", "Starting reference signal: $label")

        testToneJob = engineScope.launch {
            _metrics.value = _metrics.value.copy(isTestTonePlaying = true, activeTestToneType = label)
            val sampleRate = 48000
            val frameCount = sampleRate / 10
            val pcmShortBuf = ShortArray(frameCount * 2)
            val amplitude = 8230.0

            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
            var phase = 0.0
            val phaseInc = 2.0 * PI * 1000.0 / sampleRate

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcmShortBuf.size * 2 * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()

            if (track == null) {
                _metrics.value = _metrics.value.copy(isTestTonePlaying = false, activeTestToneType = "NONE")
                return@launch
            }

            try {
                track.play()
                val totalBlocks = 40
                val dsp = HyperPlayPlaybackService.dspAudioProcessor

                for (block in 0 until totalBlocks) {
                    if (!isActive) break
                    val blockEnv = when (block) {
                        0 -> 0.5
                        totalBlocks - 1 -> 0.3
                        else -> 1.0
                    }

                    for (i in 0 until frameCount) {
                        val rawNormalized = if (isPinkNoise) {
                            val white = Random.nextDouble(-1.0, 1.0)
                            b0 = 0.99886 * b0 + white * 0.0555179
                            b1 = 0.99332 * b1 + white * 0.0750759
                            b2 = 0.96900 * b2 + white * 0.1538520
                            b3 = 0.86650 * b3 + white * 0.3104856
                            b4 = 0.55000 * b4 + white * 0.5329522
                            b5 = -0.7616 * b5 - white * 0.0168980
                            val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.045
                            b6 = white * 0.115926
                            pink.coerceIn(-0.95, 0.95) * 0.25 * blockEnv
                        } else {
                            val s = sin(phase) * (amplitude / 32768.0) * blockEnv
                            phase += phaseInc
                            if (phase > 2.0 * PI) phase -= 2.0 * PI
                            s
                        }

                        val (outL, outR) = dsp.processNormalizedStereoSampleForTest(rawNormalized, rawNormalized, sampleRate)
                        pcmShortBuf[i * 2] = (outL * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                        pcmShortBuf[i * 2 + 1] = (outR * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                    }
                    track.write(pcmShortBuf, 0, pcmShortBuf.size)
                }
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
                _metrics.value = _metrics.value.copy(isTestTonePlaying = false, activeTestToneType = "NONE")
                recordLog("TEST_SIGNAL_STOP", "Finished reference signal: $label")
            }
        }
    }

    fun stopReferenceSignal() {
        testToneJob?.cancel()
        testToneJob = null
        _metrics.value = _metrics.value.copy(isTestTonePlaying = false, activeTestToneType = "NONE")
    }

    private fun startMetricsAggregatorLoop() {
        engineScope.launch {
            lastCpuTimeMs = Process.getElapsedCpuTime()
            lastWallTimeMs = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(1000L)
                val dsp = HyperPlayPlaybackService.dspAudioProcessor
                val snap = dsp.pollAndResetOneSecondMetrics()

                val nowCpu = Process.getElapsedCpuTime()
                val nowWall = SystemClock.elapsedRealtime()
                val wallDelta = (nowWall - lastWallTimeMs).coerceAtLeast(1L)
                val cpuDelta = (nowCpu - lastCpuTimeMs).coerceAtLeast(0L)
                lastCpuTimeMs = nowCpu
                lastWallTimeMs = nowWall
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val cpuPercent = ((cpuDelta.toFloat() / wallDelta.toFloat()) * 100f / cores).coerceIn(0f, 100f)

                _metrics.value = _metrics.value.copy(
                    sampleRateHz = snap.sampleRateHz,
                    channelCount = snap.channelCount,
                    encodingName = snap.encodingName,
                    inputPeakDbFs = linearToDbFs(snap.inPeak),
                    inputRmsDbFs = linearToDbFs(snap.inRms),
                    outputPeakDbFs = linearToDbFs(snap.outPeak),
                    outputRmsDbFs = linearToDbFs(snap.outRms),
                    autoHeadroomAttenuationDb = snap.headroomDb,
                    limiterGainReductionDb = snap.limiterGrDb,
                    clippedSamplesCount = snap.totalClippedSamples,
                    estimatedAppCpuPercent = cpuPercent
                )
            }
        }
    }

    private fun linearToDbFs(linear: Double): Float {
        if (linear <= 0.000015 || !linear.isFinite()) return -96.0f
        return (20.0 * log10(linear)).toFloat().coerceIn(-96.0f, 6.0f)
    }

    fun recordLog(event: String, detail: String) {
        val now = System.currentTimeMillis()
        val timeStr = DateFormat.format("HH:mm:ss", Date(now)).toString()
        val item = AudioDiagLogItem(now, timeStr, event, detail)
        synchronized(logLock) {
            if (logBuffer.size >= MAX_LOG_ITEMS) logBuffer.removeFirst()
            logBuffer.addLast(item)
            _logsFlow.value = logBuffer.toList().asReversed()
        }
        Log.i(LOG_TAG, "[$event] $detail")
    }

    fun clearLogs() {
        synchronized(logLock) {
            logBuffer.clear()
            _logsFlow.value = emptyList()
        }
    }

    fun formatReportSection(): String {
        val sw = _abSwitches.value
        val m = _metrics.value
        val recentLogs = _logsFlow.value.take(25)
        return buildString {
            appendLine("=== Audio Diagnostics (A/B & Real-Time Telemetry) ===")
            appendLine("Format: ${m.sampleRateHz} Hz • ${m.channelCount} ch • ${m.encodingName} • Decoder: ${m.decoderName}")
            appendLine(String.format(Locale.US, "Input Level:  Peak %.1f dBFS | RMS %.1f dBFS", m.inputPeakDbFs, m.inputRmsDbFs))
            appendLine(String.format(Locale.US, "Output Level: Peak %.1f dBFS | RMS %.1f dBFS", m.outputPeakDbFs, m.outputRmsDbFs))
            appendLine(String.format(Locale.US, "Auto-Headroom: %.1f dB | Limiter GR: %.1f dB", m.autoHeadroomAttenuationDb, m.limiterGainReductionDb))
            appendLine("Clipped Samples: ${m.clippedSamplesCount} | Underruns: ${m.underrunCount} | App CPU: ${"%.1f".format(Locale.US, m.estimatedAppCpuPercent)}%")
            appendLine("A/B Switches: pureBypass=${sw.pureBypassAll}, swDsp=${sw.softwareDspEnabled}, autoHeadroom=${sw.autoHeadroomEnabled}, dither16=${sw.dither16BitEnabled}, hwEq=${sw.hardwareEqualizerEnabled}, hwBass=${sw.hardwareBassBoostEnabled}, hwVirt=${sw.hardwareVirtualizerEnabled}, loudness=${sw.loudnessEnhancerEnabled}, pauseScanner=${sw.pauseScannerDuringPlayback}")
            if (recentLogs.isNotEmpty()) {
                appendLine("--- Recent Audio Events (${recentLogs.size}) ---")
                recentLogs.forEach { l ->
                    appendLine("${l.timeFormatted} [${l.event}] ${l.detail}")
                }
            }
        }
    }
}
