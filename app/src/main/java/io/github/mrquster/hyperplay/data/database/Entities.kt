package io.github.mrquster.hyperplay.data.database

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.mrquster.hyperplay.data.AudioTrackItem
import java.io.File

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["genre"]),
        Index(value = ["folderPath"]),
        Index(value = ["dateAddedSec"]),
        Index(value = ["searchKey"])
    ]
)
data class TrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val genre: String,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    val contentUriString: String,
    val artworkUriString: String,
    val filePath: String,
    val folderPath: String,
    val folderName: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val mimeType: String,
    val searchKey: String
)

/**
 * Отдельная таблица пользовательской статистики (Избранное, счётчик прослушиваний, дата прослушивания),
 * которая переживает полную переиндексацию MediaStore.
 */
@Entity(tableName = "track_stats")
data class TrackStatsEntity(
    @PrimaryKey val trackId: Long,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedEpochMs: Long = 0L
)

data class TrackWithStatsPojo(
    @Embedded val track: TrackEntity,
    @ColumnInfo(name = "isFavorite") val isFavorite: Boolean?,
    @ColumnInfo(name = "playCount") val playCount: Int?,
    @ColumnInfo(name = "lastPlayedEpochMs") val lastPlayedEpochMs: Long?
) {
    fun toDomain(): AudioTrackItem = AudioTrackItem(
        id = track.id,
        title = track.title,
        artist = track.artist,
        album = track.album,
        albumId = track.albumId,
        genre = track.genre,
        durationMs = track.durationMs,
        trackNumber = track.trackNumber,
        year = track.year,
        contentUri = Uri.parse(track.contentUriString),
        artworkUri = Uri.parse(track.artworkUriString),
        filePath = track.filePath,
        folderPath = track.folderPath,
        folderName = track.folderName.ifEmpty { File(track.folderPath).name.ifEmpty { "Music" } },
        sizeBytes = track.sizeBytes,
        dateAddedSec = track.dateAddedSec,
        mimeType = track.mimeType,
        isFavorite = isFavorite ?: false,
        playCount = playCount ?: 0,
        lastPlayedEpochMs = lastPlayedEpochMs ?: 0L
    )
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0L,
    val name: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId", "positionInPlaylist"]),
        Index(value = ["trackId"])
    ]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: Long,
    val positionInPlaylist: Int
)

data class PlaylistWithCountPojo(
    val playlistId: Long,
    val name: String,
    val createdAtEpochMs: Long,
    val trackCount: Int
)
