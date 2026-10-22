package io.github.mrquster.hyperplay.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Immutable
data class LyricLine(
    val timeMs: Long,
    val text: String
)

@Immutable
data class ParsedLyrics(
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val sourceLabel: String
)

object LyricsParser {

    private val LRC_LINE_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?](.*)""")

    /**
     * Ищет локальный .lrc файл с тем же именем, что и у аудиофайла, либо встроенный текст в тегах файла.
     */
    suspend fun loadLyricsForTrack(filePath: String): ParsedLyrics? = withContext(Dispatchers.IO) {
        if (filePath.isBlank()) return@withContext null
        val audioFile = File(filePath)
        if (!audioFile.exists()) return@withContext null

        // 1. Проверяем внешний файл <track_name>.lrc в той же папке
        val lrcFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
        if (lrcFile.exists() && lrcFile.canRead() && lrcFile.length() in 8..262_144) {
            val rawText = runCatching { lrcFile.readText(Charsets.UTF_8) }.getOrDefault("")
            val parsed = parseLrcText(rawText, sourceLabel = lrcFile.name)
            if (parsed != null) return@withContext parsed
        }

        // 2. Ищем встроенные синхронные или обычные тексты внутри тегов аудиофайла (Vorbis LYRICS / ID3 USLT)
        val embeddedText = extractEmbeddedLyricsFromFile(audioFile)
        if (!embeddedText.isNullOrBlank()) {
            return@withContext parseLrcText(embeddedText, sourceLabel = "Embedded Tag")
        }

        null
    }

    fun parseLrcText(rawContent: String, sourceLabel: String = ".lrc"): ParsedLyrics? {
        if (rawContent.isBlank()) return null
        val syncedLines = ArrayList<LyricLine>()
        val plainLines = ArrayList<LyricLine>()

        rawContent.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            val match = LRC_LINE_REGEX.matchEntire(trimmed)
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = match.groupValues[3]
                val millis = when (fracStr.length) {
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    3 -> fracStr.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val lyricText = match.groupValues[4].trim()
                if (lyricText.isNotEmpty()) {
                    val totalMs = min * 60_000L + sec * 1_000L + millis
                    syncedLines.add(LyricLine(timeMs = totalMs, text = lyricText))
                }
            } else if (!trimmed.startsWith("[")) {
                plainLines.add(LyricLine(timeMs = 0L, text = trimmed))
            }
        }

        return when {
            syncedLines.isNotEmpty() -> ParsedLyrics(
                isSynced = true,
                lines = syncedLines.sortedBy { it.timeMs },
                sourceLabel = sourceLabel
            )
            plainLines.isNotEmpty() -> ParsedLyrics(
                isSynced = false,
                lines = plainLines,
                sourceLabel = sourceLabel
            )
            else -> null
        }
    }

    /**
     * Находит индекс активной строки для текущей позиции плеера positionMs
     */
    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var activeIdx = 0
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs + 150L) {
                activeIdx = i
            } else {
                break
            }
        }
        return activeIdx
    }

    private fun extractEmbeddedLyricsFromFile(file: File): String? {
        return runCatching {
            val readLen = minOf(file.length(), 131_072L).toInt()
            val bytes = ByteArray(readLen)
            file.inputStream().use { it.read(bytes, 0, readLen) }
            val text = String(bytes, Charsets.UTF_8)

            // Vorbis Comment LYRICS=... с таймкодами [00:...
            val lrcBlockMatch = Regex("""(\[\d{2}:\d{2}[.:]\d{2,3}][^\u0000]+)""").find(text)
            lrcBlockMatch?.groupValues?.getOrNull(1)?.take(8192)
        }.getOrNull()
    }
}
