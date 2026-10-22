package io.github.mrquster.hyperplay.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mrquster.hyperplay.HyperPlayApp
import io.github.mrquster.hyperplay.data.*
import io.github.mrquster.hyperplay.ui.theme.ArtworkPaletteExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HyperPlayApp
    private val scanner = app.audioScanner
    val libraryRepo = app.libraryRepository
    val prefsRepo = app.playbackPrefs
    private val playerRepo = app.playerRepository
    val audioEffects = app.audioEffectsController

    val isScanning = scanner.isScanning
    val playbackState = playerRepo.playbackState
    val playbackSettings = prefsRepo.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PlaybackSettings()
    )

    // 5 вкладок нижней панели с линзой: 0=Библиотека, 1=Плейлисты, 2=Звук/Эквалайзер, 3=Настройки, 4=Система
    private val _selectedBottomTab = MutableStateFlow(0)
    val selectedBottomTab = _selectedBottomTab.asStateFlow()

    private val _isNowPlayingExpanded = MutableStateFlow(false)
    val isNowPlayingExpanded = _isNowPlayingExpanded.asStateFlow()

    private val _artworkAccentColor = MutableStateFlow(Color(0xFF00F5D4))
    val artworkAccentColor = _artworkAccentColor.asStateFlow()

    private val _currentTrackLyrics = MutableStateFlow<ParsedLyrics?>(null)
    val currentTrackLyrics = _currentTrackLyrics.asStateFlow()

    private val _selectedSection = MutableStateFlow(LibrarySection.TRACKS)
    val selectedSection = _selectedSection.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _activeFilterTitle = MutableStateFlow<String?>(null)
    val activeFilterTitle = _activeFilterTitle.asStateFlow()

    private val _activePlaylistId = MutableStateFlow<Long?>(null)
    val activePlaylistId = _activePlaylistId.asStateFlow()

    private val _inspectedTrackDetails = MutableStateFlow<AudioFileTechDetails?>(null)
    val inspectedTrackDetails = _inspectedTrackDetails.asStateFlow()

    val allTracks = libraryRepo.allTracksFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favoriteTracks = libraryRepo.favoriteTracksFlow.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val recentlyAddedTracks = libraryRepo.recentlyAddedFlow.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val recentlyPlayedTracks = libraryRepo.recentlyPlayedFlow.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val mostPlayedTracks = libraryRepo.mostPlayedFlow.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val albums = libraryRepo.albumsFlow.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val artists = libraryRepo.artistsFlow.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val genres = libraryRepo.genresFlow.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val folders = libraryRepo.foldersFlow.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val playlists = libraryRepo.playlistsFlow.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val currentPlayingTrackItem: StateFlow<AudioTrackItem?> = combine(
        allTracks,
        playbackState
    ) { tracks, pb ->
        tracks.firstOrNull { it.id.toString() == pb.currentMediaId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val visibleTracks: StateFlow<List<AudioTrackItem>> = combine(
        allTracks,
        favoriteTracks,
        recentlyAddedTracks,
        recentlyPlayedTracks,
        mostPlayedTracks,
        _selectedSection,
        _searchQuery,
        _activeFilterTitle
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<AudioTrackItem>
        @Suppress("UNCHECKED_CAST")
        val favs = values[1] as List<AudioTrackItem>
        @Suppress("UNCHECKED_CAST")
        val recAdded = values[2] as List<AudioTrackItem>
        @Suppress("UNCHECKED_CAST")
        val recPlayed = values[3] as List<AudioTrackItem>
        @Suppress("UNCHECKED_CAST")
        val mostPlayed = values[4] as List<AudioTrackItem>
        val section = values[5] as LibrarySection
        val query = values[6] as String
        val filterVal = values[7] as String?

        val base = when (section) {
            LibrarySection.FAVORITES -> favs
            LibrarySection.RECENTLY_ADDED -> recAdded
            LibrarySection.RECENTLY_PLAYED -> recPlayed
            LibrarySection.MOST_PLAYED -> mostPlayed
            LibrarySection.ALBUMS -> if (filterVal != null) all.filter { it.album == filterVal } else all
            LibrarySection.ARTISTS -> if (filterVal != null) all.filter { it.artist == filterVal } else all
            LibrarySection.GENRES -> if (filterVal != null) all.filter { it.genre == filterVal } else all
            LibrarySection.FOLDERS -> if (filterVal != null) all.filter { it.folderPath == filterVal } else all
            else -> all
        }
        libraryRepo.filterTracksBySearchQuery(base, query)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        playerRepo.connectIfNeeded()

        viewModelScope.launch {
            allTracks.collect { list ->
                if (list.isNotEmpty()) {
                    playerRepo.restoreSavedQueueIfPossible()
                }
            }
        }

        // При смене текущего трека извлекаем цвет обложки через Palette и ищем локальный .lrc
        viewModelScope.launch {
            currentPlayingTrackItem.collectLatest { track ->
                if (track == null) {
                    _currentTrackLyrics.value = null
                    return@collectLatest
                }
                val artModel = track.resolveBestArtworkModel()
                _artworkAccentColor.value = ArtworkPaletteExtractor.extractAccentColor(app, artModel)
                _currentTrackLyrics.value = LyricsParser.loadLyricsForTrack(track.filePath)
            }
        }
    }

    fun selectBottomTab(index: Int) {
        val clamped = index.coerceIn(0, 4)
        _selectedBottomTab.value = clamped
        if (clamped == 1) {
            _selectedSection.value = LibrarySection.PLAYLISTS
        } else if (clamped == 0 && _selectedSection.value == LibrarySection.PLAYLISTS) {
            _selectedSection.value = LibrarySection.TRACKS
        }
    }

    fun setNowPlayingExpanded(expanded: Boolean) {
        _isNowPlayingExpanded.value = expanded
    }

    fun onPermissionGranted() {
        scanner.registerObserverIfNeeded()
        scanner.triggerRescan()
    }

    fun rescanLibrary() = scanner.triggerRescan()

    fun selectSection(section: LibrarySection) {
        _selectedSection.value = section
        _activeFilterTitle.value = null
        _activePlaylistId.value = null
    }

    fun openSubFilter(section: LibrarySection, filterKey: String, playlistId: Long? = null) {
        _selectedSection.value = section
        _activeFilterTitle.value = filterKey
        _activePlaylistId.value = playlistId
    }

    fun clearSubFilter() {
        _activeFilterTitle.value = null
        _activePlaylistId.value = null
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun setSortOption(option: SortOption) {
        viewModelScope.launch { prefsRepo.updateSortOption(option) }
    }

    fun playFromCurrentList(tracksList: List<AudioTrackItem>, index: Int) {
        playerRepo.playQueueFromIndex(tracksList, index)
    }

    fun playNext(track: AudioTrackItem) = playerRepo.playNextInQueue(track)
    fun addToQueue(track: AudioTrackItem) = playerRepo.addToEndOfQueue(track)
    fun moveQueueItem(from: Int, to: Int) = playerRepo.moveQueueItem(from, to)
    fun removeQueueItem(index: Int) = playerRepo.removeQueueItemAt(index)
    fun skipToQueueItem(index: Int) = playerRepo.skipToQueueIndex(index)

    fun toggleFavorite(trackId: Long) {
        viewModelScope.launch { libraryRepo.toggleFavorite(trackId) }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { libraryRepo.createPlaylist(name) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { libraryRepo.deletePlaylist(playlistId) }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch { libraryRepo.addTrackToPlaylist(playlistId, trackId) }
    }

    fun inspectTrack(track: AudioTrackItem) {
        viewModelScope.launch {
            _inspectedTrackDetails.value = CoverArtAndTagReader.inspectTrackFile(app, track)
        }
    }

    fun dismissTrackDetails() {
        _inspectedTrackDetails.value = null
    }

    suspend fun exportPlaylistM3u(playlistId: Long, name: String): String {
        val tracks = libraryRepo.getPlaylistTracksOnce(playlistId)
        return M3uPlaylistIO.exportToM3uString(name, tracks)
    }

    fun importM3uPlaylist(name: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val matchedIds = M3uPlaylistIO.parseM3uAndMatchTracks(content, allTracks.value)
            if (matchedIds.isNotEmpty()) {
                val newId = libraryRepo.createPlaylist(name)
                libraryRepo.reorderPlaylist(newId, matchedIds)
            }
        }
    }

    fun playExternalFile(uri: Uri) {
        playerRepo.connectIfNeeded()
        playerRepo.playExternalUri(uri)
    }

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.updatePlaybackFlags(skipSilence = enabled) }
    }

    fun togglePlayPause() = playerRepo.togglePlayPause()
    fun nextTrack() = playerRepo.seekToNext()
    fun previousTrack() = playerRepo.seekToPrevious()
    fun seekTo(positionMs: Long) = playerRepo.seekToPositionMs(positionMs)
    fun toggleShuffle() = playerRepo.toggleShuffle()
    fun cycleRepeat() = playerRepo.cycleRepeatMode()
    fun startSleepTimer(minutes: Int, stopAtEnd: Boolean = false) =
        playerRepo.startSleepTimer(minutes, stopAtEnd)

    fun cycleSpeed() {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val curr = playbackState.value.playbackSpeed
        val next = speeds.firstOrNull { it > curr + 0.01f } ?: 1.0f
        playerRepo.setPlaybackSpeed(next)
    }
}
