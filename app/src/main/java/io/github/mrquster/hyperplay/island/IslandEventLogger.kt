package io.github.mrquster.hyperplay.island

import android.text.format.DateFormat
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date

data class IslandLogEntry(
    val timestampMs: Long,
    val formattedTime: String,
    val seqId: Long,
    val action: String,
    val strategyName: String,
    val trackTitle: String,
    val progressPercent: Int,
    val jsonSizeBytes: Int,
    val isSuccess: Boolean,
    val detail: String
)

data class IslandSelfTestStep(
    val title: String,
    val passed: Boolean,
    val detail: String
)

object IslandEventLogger {

    const val LOG_TAG = "HyperPlay/Island"
    private const val MAX_ENTRIES = 100

    private val lock = Any()
    private val buffer = ArrayDeque<IslandLogEntry>(MAX_ENTRIES)

    private val _entriesFlow = MutableStateFlow<List<IslandLogEntry>>(emptyList())
    val entriesFlow = _entriesFlow.asStateFlow()

    private val _selfTestFlow = MutableStateFlow<List<IslandSelfTestStep>>(emptyList())
    val selfTestFlow = _selfTestFlow.asStateFlow()

    fun record(
        seqId: Long,
        action: String,
        strategyName: String,
        trackTitle: String,
        progressPercent: Int,
        jsonSizeBytes: Int,
        isSuccess: Boolean,
        detail: String
    ) {
        val now = System.currentTimeMillis()
        val timeStr = DateFormat.format("HH:mm:ss", Date(now)).toString()
        val safeTitle = trackTitle.take(22).ifEmpty { "Idle" }
        val entry = IslandLogEntry(
            timestampMs = now,
            formattedTime = timeStr,
            seqId = seqId,
            action = action,
            strategyName = strategyName,
            trackTitle = safeTitle,
            progressPercent = progressPercent,
            jsonSizeBytes = jsonSizeBytes,
            isSuccess = isSuccess,
            detail = detail
        )

        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entriesFlow.value = buffer.toList().asReversed()
        }

        val msg = "[seq=#$seqId] $action | $strategyName | \"$safeTitle\" ($progressPercent%) | ${jsonSizeBytes}B | ok=$isSuccess: $detail"
        if (isSuccess) Log.i(LOG_TAG, msg) else Log.w(LOG_TAG, msg)
    }

    fun updateSelfTest(steps: List<IslandSelfTestStep>) {
        _selfTestFlow.value = steps
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entriesFlow.value = emptyList()
            _selfTestFlow.value = emptyList()
        }
    }

    fun formatPlainText(): String {
        val list = _entriesFlow.value
        if (list.isEmpty()) return "HyperPlay Island Log is empty."
        return buildString {
            appendLine("=== HyperPlay Island Event Log (${list.size} entries) ===")
            list.forEach { e ->
                val mark = if (e.isSuccess) "✓" else "✗"
                appendLine("${e.formattedTime} $mark [#${e.seqId}] ${e.action} | ${e.trackTitle} (${e.progressPercent}%) | ${e.jsonSizeBytes}B -> ${e.detail}")
            }
        }
    }
}
