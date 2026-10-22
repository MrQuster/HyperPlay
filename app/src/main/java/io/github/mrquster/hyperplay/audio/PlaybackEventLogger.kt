package io.github.mrquster.hyperplay.audio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DropoutReasonCategory(
    val code: String,
    val labelEn: String,
    val labelRu: String,
    val hintEn: String,
    val hintRu: String
) {
    AUDIO_FOCUS(
        "AUDIO_FOCUS",
        "Audio Focus Interrupted (Notification / Other App)",
        "Перехват аудиофокуса (уведомление или другое приложение)",
        "Another app or system notification sound briefly requested audio focus (ducking/pause). Mute notification sounds to verify.",
        "Системный звук уведомления или другое приложение кратковременно запросило аудиофокус (приглушение/пауза). Проверьте в режиме «Не беспокоить»."
    ),
    BECOMING_NOISY(
        "BECOMING_NOISY",
        "Audio Route Disconnected (Becoming Noisy)",
        "Отключение аудиомаршрута (Becoming Noisy)",
        "Headphones or Bluetooth A2DP link reported a disconnect or codec renegotiation.",
        "Проводные наушники или Bluetooth-канал сообщили о разрыве связи или смене кодека."
    ),
    BUFFER_UNDERRUN(
        "BUFFER_UNDERRUN",
        "AudioTrack Buffer Underrun / Slow Storage Read",
        "Недогрузка буфера AudioTrack / медленное чтение файла",
        "AudioSink ran out of PCM frames. Enable 'Safe Anti-Clip Mode' or pause background library scanning.",
        "Опустел буфер AudioTrack. Включите «Безопасный режим» или отключите фоновое сканирование во время игры."
    ),
    ROUTE_CHANGE(
        "ROUTE_CHANGE",
        "Audio Output Route / Codec Switch",
        "Смена аудиовыхода или Bluetooth-кодека",
        "System switched audio output device or renegotiated Bluetooth sample rate.",
        "Система переключила устройство вывода или изменила частоту Bluetooth-кодека."
    ),
    MAIN_THREAD_JANK(
        "MAIN_THREAD_JANK",
        "Main Thread Stall (>140 ms)",
        "Задержка главного потока (>140 мс)",
        "UI thread was busy for >140 ms. Enable Battery Saver Glass mode to reduce GPU/CPU load.",
        "Главный поток был занят дольше 140 мс. Включите «Упрощённое стекло» для разгрузки процессора."
    ),
    PLAYER_ERROR(
        "PLAYER_ERROR",
        "Decoder / MediaSource Error",
        "Ошибка декодера или повреждения файла",
        "MediaCodec reported an error on the current file.",
        "Декодер сообщил об ошибке чтения текущего аудиофайла."
    )
}

@Immutable
data class PlaybackLogEvent(
    val id: Long,
    val elapsedRealtimeMs: Long,
    val wallTimeFormatted: String,
    val category: String,
    val message: String,
    val isDropoutMarker: Boolean = false
)

@Immutable
data class DropoutIncident(
    val index: Int,
    val timestampFormatted: String,
    val reason: DropoutReasonCategory,
    val trackTitle: String,
    val outputDevice: String,
    val detail: String,
    val contextWindowEvents: List<String>
)

@Immutable
data class DropoutStressTestState(
    val isRunning: Boolean = false,
    val targetDurationSec: Int = 300,
    val elapsedSec: Int = 0,
    val dropoutsDetectedDuringTest: Int = 0,
    val summaryText: String = ""
)

object PlaybackEventLogger {

    const val LOG_TAG = "HyperPlay/PlaybackLog"
    private const val MAX_EVENTS = 300
    private const val CONTEXT_WINDOW_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val lock = Any()
    private var nextEventId = 1L
    private val eventRing = ArrayDeque<PlaybackLogEvent>(MAX_EVENTS)
    private val incidentsList = ArrayList<DropoutIncident>()

    @Volatile var isPlayerActivelyPlaying: Boolean = false
    @Volatile var isPlayerBuffering: Boolean = false
    @Volatile var userIntendedPlay: Boolean = false
    @Volatile var currentTrackLabel: String = ""
    @Volatile var currentOutputLabel: String = "Speaker"

    private val _eventsFlow = MutableStateFlow<List<PlaybackLogEvent>>(emptyList())
    val eventsFlow = _eventsFlow.asStateFlow()

    private val _incidentsFlow = MutableStateFlow<List<DropoutIncident>>(emptyList())
    val incidentsFlow = _incidentsFlow.asStateFlow()

    private val _stressTestState = MutableStateFlow(DropoutStressTestState())
    val stressTestState = _stressTestState.asStateFlow()

    private var watchdogJob: Job? = null
    private var stressTestJob: Job? = null

    init {
        startMainThreadWatchdog()
    }

    fun logEvent(
        category: String,
        message: String,
        isDropoutMarker: Boolean = false
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val wallStr = synchronized(timeFormat) { timeFormat.format(Date(System.currentTimeMillis())) }

        synchronized(lock) {
            val ev = PlaybackLogEvent(
                id = nextEventId++,
                elapsedRealtimeMs = nowElapsed,
                wallTimeFormatted = wallStr,
                category = category,
                message = message,
                isDropoutMarker = isDropoutMarker
            )
            if (eventRing.size >= MAX_EVENTS) {
                eventRing.removeFirst()
            }
            eventRing.addLast(ev)
            _eventsFlow.value = eventRing.toList().asReversed()
        }

        if (isDropoutMarker) {
            Log.w(LOG_TAG, "⚠️ [DROPOUT:$category] $message")
        } else {
            Log.i(LOG_TAG, "[$category] $message")
        }
    }

    /**
     * Фиксирует инцидент прерывания звука с сохранением 5-секундного окна предшествующих событий
     */
    fun reportDropoutIncident(
        reason: DropoutReasonCategory,
        detail: String
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val wallStr = synchronized(timeFormat) { timeFormat.format(Date(System.currentTimeMillis())) }

        logEvent(
            category = "ПРЕРЫВАНИЕ / DROPOUT",
            message = "${reason.code}: $detail (track=\"$currentTrackLabel\", out=$currentOutputLabel)",
            isDropoutMarker = true
        )

        synchronized(lock) {
            val contextEvents = eventRing
                .filter { nowElapsed - it.elapsedRealtimeMs <= CONTEXT_WINDOW_MS }
                .takeLast(12)
                .map { "${it.wallTimeFormatted} [${it.category}] ${it.message}" }

            val incident = DropoutIncident(
                index = incidentsList.size + 1,
                timestampFormatted = wallStr,
                reason = reason,
                trackTitle = currentTrackLabel.ifEmpty { "Unknown" },
                outputDevice = currentOutputLabel,
                detail = detail,
                contextWindowEvents = contextEvents
            )
            incidentsList.add(0, incident)
            if (incidentsList.size > 50) {
                incidentsList.removeLast()
            }
            _incidentsFlow.value = incidentsList.toList()
        }

        if (_stressTestState.value.isRunning) {
            val st = _stressTestState.value
            _stressTestState.value = st.copy(
                dropoutsDetectedDuringTest = st.dropoutsDetectedDuringTest + 1
            )
        }
    }

    fun startDropoutStressTest(durationMinutes: Int = 5) {
        if (stressTestJob?.isActive == true) {
            stopDropoutStressTest()
            return
        }
        val totalSec = (durationMinutes.coerceIn(1, 30)) * 60
        logEvent("STRESS_TEST", "Started ${durationMinutes}-min continuous dropout monitoring test")

        stressTestJob = scope.launch {
            _stressTestState.value = DropoutStressTestState(
                isRunning = true,
                targetDurationSec = totalSec,
                elapsedSec = 0,
                dropoutsDetectedDuringTest = 0,
                summaryText = "Monitoring active playback for dropouts..."
            )

            for (sec in 1..totalSec) {
                if (!isActive) break
                delay(1000L)
                val curr = _stressTestState.value
                _stressTestState.value = curr.copy(
                    elapsedSec = sec,
                    summaryText = "Elapsed: ${sec}s / ${totalSec}s • Dropouts: ${curr.dropoutsDetectedDuringTest}"
                )
            }

            val finalState = _stressTestState.value
            val verdict = if (finalState.dropoutsDetectedDuringTest == 0) {
                "✓ PASS: 0 dropouts detected in ${finalState.elapsedSec}s on $currentOutputLabel"
            } else {
                "⚠ DETECTED ${finalState.dropoutsDetectedDuringTest} dropout(s) in ${finalState.elapsedSec}s. Check incident details above."
            }
            _stressTestState.value = finalState.copy(
                isRunning = false,
                summaryText = verdict
            )
            logEvent("STRESS_TEST_DONE", verdict)
        }
    }

    fun stopDropoutStressTest() {
        stressTestJob?.cancel()
        stressTestJob = null
        val curr = _stressTestState.value
        if (curr.isRunning) {
            val verdict = "Stopped at ${curr.elapsedSec}s • Dropouts: ${curr.dropoutsDetectedDuringTest}"
            _stressTestState.value = curr.copy(isRunning = false, summaryText = verdict)
            logEvent("STRESS_TEST_STOP", verdict)
        }
    }

    /**
     * Сторожевой таймер главного потока: работает ТОЛЬКО во время воспроизведения (0 нагрузки на паузе).
     * Если главный поток отвечает дольше 180 мс, записывает событие в журнал.
     */
    private fun startMainThreadWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                if (!isPlayerActivelyPlaying) {
                    delay(1000L)
                    continue
                }
                val pingSentAt = SystemClock.elapsedRealtime()
                val completed = CompletableDeferred<Long>()
                mainHandler.post {
                    completed.complete(SystemClock.elapsedRealtime() - pingSentAt)
                }
                val latencyMs = withTimeoutOrNull(1500L) { completed.await() } ?: 1500L
                if (latencyMs >= 180L && isPlayerActivelyPlaying) {
                    logEvent("MAIN_THREAD_STALL", "Main thread delayed by ${latencyMs} ms during playback")
                    if (latencyMs >= 450L) {
                        reportDropoutIncident(
                            reason = DropoutReasonCategory.MAIN_THREAD_JANK,
                            detail = "Main Looper blocked for ${latencyMs} ms"
                        )
                    }
                }
                delay(500L)
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            eventRing.clear()
            incidentsList.clear()
            _eventsFlow.value = emptyList()
            _incidentsFlow.value = emptyList()
        }
    }

    fun formatPlaybackLogReport(isRu: Boolean): String {
        val incidents = _incidentsFlow.value
        val events = _eventsFlow.value.take(80)
        return buildString {
            appendLine("=== HyperPlay Playback & Dropout Log (${events.size} recent / ${incidents.size} dropouts) ===")
            if (incidents.isEmpty()) {
                appendLine("Dropouts Detected: 0 (Clean playback)")
            } else {
                appendLine("--- Detected Dropouts (${incidents.size}) ---")
                incidents.take(10).forEach { inc ->
                    val reasonTitle = if (isRu) inc.reason.labelRu else inc.reason.labelEn
                    appendLine("#${inc.index} at ${inc.timestampFormatted} | $reasonTitle | Track: ${inc.trackTitle} | Out: ${inc.outputDevice}")
                    appendLine("   Detail: ${inc.detail}")
                    inc.contextWindowEvents.forEach { ctxLine ->
                        appendLine("     -> $ctxLine")
                    }
                }
            }
            appendLine("--- Recent Playback Timeline ---")
            events.forEach { e ->
                val mark = if (e.isDropoutMarker) "⚠️ " else ""
                appendLine("${e.wallTimeFormatted} $mark[${e.category}] ${e.message}")
            }
        }
    }
}
