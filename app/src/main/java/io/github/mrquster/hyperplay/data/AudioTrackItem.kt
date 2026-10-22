package io.github.mrquster.hyperplay.data

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File

@Immutable
data class AudioTrackItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val genre: String,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    val contentUri: Uri,
    val artworkUri: Uri,
    val filePath: String,
    val folderPath: String,
    val folderName: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val mimeType: String,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedEpochMs: Long = 0L
) {
    fun toMedia3Item(): MediaItem {
        val extras = Bundle().apply {
            putString("filePath", filePath)
            putString("folderPath", folderPath)
            putString("mimeType", mimeType)
            putLong("sizeBytes", sizeBytes)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setGenre(genre)
            .setReleaseYear(year.takeIf { it > 0 })
            .setTrackNumber(trackNumber.takeIf { it > 0 })
            .setArtworkUri(artworkUri)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(contentUri)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Возвращает локальный файл cover.jpg / folder.jpg рядом с треком, если он существует,
     * иначе URI системной обложки альбома.
     */
    fun resolveBestArtworkModel(): Any {
        if (folderPath.isNotEmpty()) {
            val dir = File(folderPath)
            val candidates = arrayOf("cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "cover.png", "folder.png")
            for (name in candidates) {
                val f = File(dir, name)
                if (f.exists() && f.length() > 512L) {
                    return f
                }
            }
        }
        return artworkUri
    }
}

@Immutable
data class AlbumSummary(
    val albumId: Long,
    val title: String,
    val artist: String,
    val trackCount: Int,
    val year: Int,
    val artworkModel: Any
)

@Immutable
data class ArtistSummary(
    val name: String,
    val trackCount: Int,
    val albumCount: Int
)

@Immutable
data class GenreSummary(
    val name: String,
    val trackCount: Int
)

@Immutable
data class FolderSummary(
    val path: String,
    val name: String,
    val trackCount: Int
)

@Immutable
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val createdAtEpochMs: Long
)
