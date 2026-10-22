package io.github.mrquster.hyperplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("""
        SELECT t.*, s.isFavorite, s.playCount, s.lastPlayedEpochMs
        FROM tracks t
        LEFT JOIN track_stats s ON t.id = s.trackId
        ORDER BY t.title COLLATE NOCASE ASC
    """)
    fun observeAllTracksWithStats(): Flow<List<TrackWithStatsPojo>>

    @Query("""
        SELECT t.*, s.isFavorite, s.playCount, s.lastPlayedEpochMs
        FROM tracks t
        INNER JOIN track_stats s ON t.id = s.trackId
        WHERE s.isFavorite = 1
        ORDER BY t.title COLLATE NOCASE ASC
    """)
    fun observeFavoriteTracks(): Flow<List<TrackWithStatsPojo>>

    @Query("""
        SELECT t.*, s.isFavorite, s.playCount, s.lastPlayedEpochMs
        FROM tracks t
        INNER JOIN track_stats s ON t.id = s.trackId
        WHERE s.lastPlayedEpochMs > 0
        ORDER BY s.lastPlayedEpochMs DESC
        LIMIT 100
    """)
    fun observeRecentlyPlayedTracks(): Flow<List<TrackWithStatsPojo>>

    @Query("""
        SELECT t.*, s.isFavorite, s.playCount, s.lastPlayedEpochMs
        FROM tracks t
        INNER JOIN track_stats s ON t.id = s.trackId
        WHERE s.playCount > 0
        ORDER BY s.playCount DESC, s.lastPlayedEpochMs DESC
        LIMIT 100
    """)
    fun observeMostPlayedTracks(): Flow<List<TrackWithStatsPojo>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracksBatch(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id NOT IN (:validIds)")
    suspend fun deleteTracksNotIn(validIds: List<Long>)

    @Query("DELETE FROM tracks")
    suspend fun clearAllTracks()

    @Transaction
    suspend fun syncScannedTracks(scanned: List<TrackEntity>) {
        if (scanned.isEmpty()) {
            clearAllTracks()
            return
        }
        // Записываем батчами по 500 записей, чтобы не превышать лимит переменных SQLite (999) на 50 000+ треках
        scanned.chunked(500).forEach { chunk ->
            upsertTracksBatch(chunk)
        }
        val count = getTracksCount()
        if (count > scanned.size) {
            val allStoredIds = getAllTrackIds()
            val scannedSet = scanned.mapTo(HashSet(scanned.size)) { it.id }
            val toDelete = allStoredIds.filter { it !in scannedSet }
            toDelete.chunked(500).forEach { chunk ->
                deleteTracksByIds(chunk)
            }
        }
    }

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTracksCount(): Int

    @Query("SELECT id FROM tracks")
    suspend fun getAllTrackIds(): List<Long>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteTracksByIds(ids: List<Long>)

    // --- Избранное и статистика прослушиваний ---

    @Query("SELECT * FROM track_stats WHERE trackId = :trackId LIMIT 1")
    suspend fun getStatsForTrack(trackId: Long): TrackStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackStats(stats: TrackStatsEntity)

    @Transaction
    suspend fun toggleFavorite(trackId: Long): Boolean {
        val current = getStatsForTrack(trackId) ?: TrackStatsEntity(trackId = trackId)
        val newFav = !current.isFavorite
        upsertTrackStats(current.copy(isFavorite = newFav))
        return newFav
    }

    @Transaction
    suspend fun incrementPlayCount(trackId: Long, timestampMs: Long = System.currentTimeMillis()) {
        val current = getStatsForTrack(trackId) ?: TrackStatsEntity(trackId = trackId)
        upsertTrackStats(
            current.copy(
                playCount = current.playCount + 1,
                lastPlayedEpochMs = timestampMs
            )
        )
    }

    // --- Плейлисты ---

    @Query("""
        SELECT p.playlistId, p.name, p.createdAtEpochMs, COUNT(pt.trackId) AS trackCount
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON p.playlistId = pt.playlistId
        GROUP BY p.playlistId
        ORDER BY p.createdAtEpochMs DESC
    """)
    fun observePlaylistsWithCount(): Flow<List<PlaylistWithCountPojo>>

    @Query("""
        SELECT t.*, s.isFavorite, s.playCount, s.lastPlayedEpochMs
        FROM playlist_tracks pt
        INNER JOIN tracks t ON pt.trackId = t.id
        LEFT JOIN track_stats s ON t.id = s.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.positionInPlaylist ASC
    """)
    fun observeTracksForPlaylist(playlistId: Long): Flow<List<TrackWithStatsPojo>>

    @Query("""
        SELECT t.*, s.isFavorite, s.playCount, s.lastPlayedEpochMs
        FROM playlist_tracks pt
        INNER JOIN tracks t ON pt.trackId = t.id
        LEFT JOIN track_stats s ON t.id = s.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.positionInPlaylist ASC
    """)
    suspend fun getTracksForPlaylistOnce(playlistId: Long): List<TrackWithStatsPojo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :newName WHERE playlistId = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, newName: String)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT COALESCE(MAX(positionInPlaylist), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getMaxPositionInPlaylist(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(crossRef: PlaylistTrackCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracksBatch(crossRefs: List<PlaylistTrackCrossRef>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)

    @Transaction
    suspend fun addTrackToEndOfPlaylist(playlistId: Long, trackId: Long) {
        val nextPos = getMaxPositionInPlaylist(playlistId) + 1
        insertPlaylistTrack(PlaylistTrackCrossRef(playlistId, trackId, nextPos))
    }

    @Transaction
    suspend fun replacePlaylistOrder(playlistId: Long, orderedTrackIds: List<Long>) {
        clearPlaylistTracks(playlistId)
        val refs = orderedTrackIds.mapIndexed { index, trackId ->
            PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, positionInPlaylist = index)
        }
        insertPlaylistTracksBatch(refs)
    }
}
