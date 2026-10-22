package io.github.mrquster.hyperplay.data

import java.io.File

object M3uPlaylistIO {

    /**
     * Формирует содержимое файла .m3u8 с заголовком #EXTM3U и метаданными #EXTINF
     */
    fun exportToM3uString(playlistName: String, tracks: List<AudioTrackItem>): String {
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#PLAYLIST:$playlistName")
            tracks.forEach { track ->
                val durationSec = (track.durationMs / 1000L).coerceAtLeast(1L)
                val safeArtist = track.artist.replace("\n", " ")
                val safeTitle = track.title.replace("\n", " ")
                appendLine("#EXTINF:$durationSec,$safeArtist - $safeTitle")
                appendLine(track.filePath.ifBlank { track.contentUri.toString() })
            }
        }
    }

    /**
     * Разбирает содержимое .m3u / .m3u8 и сопоставляет строки с проиндексированными треками библиотеки
     */
    fun parseM3uAndMatchTracks(
        m3uContent: String,
        allLibraryTracks: List<AudioTrackItem>
    ): List<Long> {
        if (m3uContent.isBlank() || allLibraryTracks.isEmpty()) return emptyList()

        val byExactPath = allLibraryTracks.associateBy { it.filePath.lowercase() }
        val byFileName = allLibraryTracks.associateBy { File(it.filePath).name.lowercase() }
        val matchedIds = ArrayList<Long>()

        m3uContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(10_000) // Защита от гигантских/повреждённых файлов
            .forEach { rawLine ->
                val normalizedPath = rawLine.replace('\\', '/')
                val exactMatch = byExactPath[normalizedPath.lowercase()]
                if (exactMatch != null) {
                    matchedIds.add(exactMatch.id)
                } else {
                    val fileName = normalizedPath.substringAfterLast('/').lowercase()
                    val nameMatch = byFileName[fileName]
                    if (nameMatch != null) {
                        matchedIds.add(nameMatch.id)
                    }
                }
            }

        return matchedIds.distinct()
    }
}
