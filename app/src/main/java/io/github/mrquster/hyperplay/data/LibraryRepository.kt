package io.github.mrquster.hyperplay.data

import io.github.mrquster.hyperplay.data.database.LibraryDao
import io.github.mrquster.hyperplay.data.database.PlaylistEntity
import io.github.mrquster.hyperplay.util.TextSearchUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

enum class LibrarySection {
    TRACKS,
    ALBUMS,
    ARTISTS,
    GENRES,
    FOLDERS,
    PLAYLISTS,
    FAVORITES,
    RECENTLY_ADDED,
    RECENTLY_PLAYED,
    MOST_PLAYED
}

class LibraryRepository(
    private val dao: LibraryDao,
    private val prefsRepo: PlaybackPreferencesRepository
) {
    val allTracksFlow: Flow<List<AudioTrackItem>> = combine(
        dao.observeAllTracksWithStats(),
        prefsRepo.settingsFlow
    ) { pojos, settings ->
        val domainList = pojos.map { it.toDomain() }
        sortTracks(domainList, settings.sortOption)
    }.flowOn(Dispatchers.Default)

    val favoriteTracksFlow: Flow<List<AudioTrackItem>> =
        dao.observeFavoriteTracks().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    val recentlyPlayedFlow: Flow<List<AudioTrackItem>> =
        dao.observeRecentlyPlayedTracks().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    val mostPlayedFlow: Flow<List<AudioTrackItem>> =
        dao.observeMostPlayedTracks().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    val recentlyAddedFlow: Flow<List<AudioTrackItem>> = allTracksFlow.map { tracks ->
        tracks.sortedByDescending { it.dateAddedSec }.take(100)
    }.flowOn(Dispatchers.Default)

    val albumsFlow: Flow<List<AlbumSummary>> = allTracksFlow.map { tracks ->
        tracks.groupBy { it.albumId to it.album }
            .map { (pair, albumTracks) ->
                val first = albumTracks.first()
                AlbumSummary(
                    albumId = pair.first,
                    title = pair.second,
                    artist = first.artist,
                    trackCount = albumTracks.size,
                    year = albumTracks.maxOfOrNull { it.year } ?: 0,
                    artworkModel = first.resolveBestArtworkModel()
                )
            }
            .sortedBy { it.title.lowercase() }
    }.flowOn(Dispatchers.Default)

    val artistsFlow: Flow<List<ArtistSummary>> = allTracksFlow.map { tracks ->
        tracks.groupBy { it.artist }
            .map { (artistName, artistTracks) ->
                ArtistSummary(
                    name = artistName,
                    trackCount = artistTracks.size,
                    albumCount = artistTracks.map { it.album }.distinct().size
                )
            }
            .sortedBy { it.name.lowercase() }
    }.flowOn(Dispatchers.Default)

    val genresFlow: Flow<List<GenreSummary>> = allTracksFlow.map { tracks ->
        tracks.groupBy { it.genre }
            .map { (genreName, genreTracks) ->
                GenreSummary(name = genreName, trackCount = genreTracks.size)
            }
            .sortedBy { it.name.lowercase() }
    }.flowOn(Dispatchers.Default)

    val foldersFlow: Flow<List<FolderSummary>> = allTracksFlow.map { tracks ->
        tracks.groupBy { it.folderPath }
            .map { (path, folderTracks) ->
                FolderSummary(
                    path = path,
                    name = folderTracks.first().folderName,
                    trackCount = folderTracks.size
                )
            }
            .sortedBy { it.name.lowercase() }
    }.flowOn(Dispatchers.Default)

    val playlistsFlow: Flow<List<PlaylistSummary>> = dao.observePlaylistsWithCount().map { list ->
        list.map {
            PlaylistSummary(
                id = it.playlistId,
                name = it.name,
                trackCount = it.trackCount,
                createdAtEpochMs = it.createdAtEpochMs
            )
        }
    }

    fun observePlaylistTracks(playlistId: Long): Flow<List<AudioTrackItem>> =
        dao.observeTracksForPlaylist(playlistId).map { list -> list.map { it.toDomain() } }

    suspend fun getPlaylistTracksOnce(playlistId: Long): List<AudioTrackItem> =
        dao.getTracksForPlaylistOnce(playlistId).map { it.toDomain() }

    suspend fun toggleFavorite(trackId: Long): Boolean = dao.toggleFavorite(trackId)

    suspend fun recordTrackPlayed(trackId: Long) = dao.incrementPlayCount(trackId)

    suspend fun createPlaylist(name: String): Long =
        dao.insertPlaylist(PlaylistEntity(name = name.trim()))

    suspend fun renamePlaylist(playlistId: Long, newName: String) =
        dao.renamePlaylist(playlistId, newName.trim())

    suspend fun deletePlaylist(playlistId: Long) = dao.deletePlaylist(playlistId)

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) =
        dao.addTrackToEndOfPlaylist(playlistId, trackId)

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) =
        dao.removeTrackFromPlaylist(playlistId, trackId)

    suspend fun reorderPlaylist(playlistId: Long, orderedTrackIds: List<Long>) =
        dao.replacePlaylistOrder(playlistId, orderedTrackIds)

    fun filterTracksBySearchQuery(tracks: List<AudioTrackItem>, rawQuery: String): List<AudioTrackItem> {
        val normQuery = TextSearchUtils.normalizeForSearch(rawQuery)
        if (normQuery.isEmpty()) return tracks
        val tokens = normQuery.split(" ").filter { it.isNotEmpty() }
        return tracks.filter { track ->
            val target = TextSearchUtils.normalizeForSearch(
                "${track.title} ${track.artist} ${track.album} ${track.genre} ${track.folderName}"
            )
            tokens.all { token -> target.contains(token) }
        }
    }

    private fun sortTracks(list: List<AudioTrackItem>, option: SortOption): List<AudioTrackItem> {
        return when (option) {
            SortOption.TITLE_ASC -> list.sortedBy { it.title.lowercase() }
            SortOption.ARTIST_ASC -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            SortOption.ALBUM_ASC -> list.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNumber }))
            SortOption.DATE_ADDED_DESC -> list.sortedByDescending { it.dateAddedSec }
            SortOption.DURATION_DESC -> list.sortedByDescending { it.durationMs }
            SortOption.YEAR_DESC -> list.sortedWith(compareByDescending<AudioTrackItem> { it.year }.thenBy { it.title.lowercase() })
        }
    }
}
