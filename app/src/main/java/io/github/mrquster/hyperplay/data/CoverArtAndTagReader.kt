package io.github.mrquster.hyperplay.data

import android.os.Build

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Immutable
data class AudioFileTechDetails(
    val trackTitle: String,
    val formatMime: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val channelsCount: Int,
    val fileSizeMb: String,
    val filePath: String,
    val replayGainDb: Float?
)

object CoverArtAndTagReader {

    /**
     * Извлекает битрейт, частоту дискретизации, каналы и ReplayGain (если прописан в тегах)
     * вне главного потока.
     */
    suspend fun inspectTrackFile(
        context: Context,
        track: AudioTrackItem
    ): AudioFileTechDetails = withContext(Dispatchers.IO) {
        var bitrateKbps = 0
        var sampleRateHz = 44100
        var channels = 2
        var replayGainDb: Float? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.contentUri)
                val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                bitrateKbps = (brStr?.toIntOrNull() ?: 0) / 1000
                if (Build.VERSION.SDK_INT >= 31) {
                    val srStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    if (srStr != null) sampleRateHz = srStr.toIntOrNull() ?: sampleRateHz
                }
            } finally {
                retriever.release()
            }
        }

        // Дополнительно читаем MediaExtractor для точной частоты дискретизации FLAC / OPUS / WAV
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, track.contentUri, null)
                if (extractor.trackCount > 0) {
                    val format = extractor.getTrackFormat(0)
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (bitrateKbps == 0 && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        bitrateKbps = format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                    }
                }
            } finally {
                extractor.release()
            }
        }

        // Если битрейт не в метаданных (например у FLAC), вычисляем средний битрейт из размера файла и длительности
        if (bitrateKbps <= 0 && track.durationMs > 1000L && track.sizeBytes > 0L) {
            bitrateKbps = ((track.sizeBytes * 8L) / track.durationMs).toInt()
        }

        // Быстрый парсинг тега REPLAYGAIN_TRACK_GAIN из начала/конца файла (ID3v2 TXXX / Vorbis Comment)
        replayGainDb = parseReplayGainTagFromFile(track.filePath)

        val sizeMb = String.format(Locale.US, "%.2f MB", track.sizeBytes / (1024.0 * 1024.0))
        AudioFileTechDetails(
            trackTitle = track.title,
            formatMime = track.mimeType.ifEmpty { File(track.filePath).extension.uppercase() },
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            channelsCount = channels,
            fileSizeMb = sizeMb,
            filePath = track.filePath,
            replayGainDb = replayGainDb
        )
    }

    /**
     * Ищет текстовый тег REPLAYGAIN_TRACK_GAIN (например "-6.45 dB") в первых 64 КБ аудиофайла
     * (работает для FLAC/OGG/OPUS Vorbis Comments и MP3 ID3v2 TXXX без сторонних JNI-библиотек).
     */
    fun parseReplayGainTagFromFile(filePath: String): Float? {
        if (filePath.isBlank()) return null
        return runCatching {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) return null
            val bytesToRead = minOf(file.length(), 65536L).toInt()
            val buffer = ByteArray(bytesToRead)
            file.inputStream().use { it.read(buffer, 0, bytesToRead) }
            val ascii = String(buffer, Charsets.ISO_8859_1)
            val regex = Regex("""REPLAYGAIN_TRACK_GAIN[^\d\-+]{1,6}([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
            val match = regex.find(ascii) ?: return null
            match.groupValues.getOrNull(1)?.toFloatOrNull()?.coerceIn(-24f, 18f)
        }.getOrNull()
    }
}
