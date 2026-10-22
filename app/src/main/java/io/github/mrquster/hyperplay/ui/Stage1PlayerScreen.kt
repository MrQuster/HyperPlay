package io.github.mrquster.hyperplay.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.data.*
import io.github.mrquster.hyperplay.util.AppInfo
import io.github.mrquster.hyperplay.util.TextSearchUtils
import io.github.mrquster.hyperplay.util.TimeFormatUtils
import kotlinx.coroutines.launch

private val BgDeep = Color(0xFF070B14)
private val GlassSurface = Color(0xCC0E1629)
private val AccentMint = Color(0xFF00F5D4)
private val AccentCyan = Color(0xFF00A8E8)
private val TextWhite = Color(0xFFFFFFFF)
private val TextMuted = Color(0xFFB8C4D9)

@Composable
fun Stage1PlayerScreen(
    viewModel: PlaybackViewModel,
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val visibleTracks by viewModel.visibleTracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    val selectedSection by viewModel.selectedSection.collectAsStateWithLifecycle()
    val activeFilterTitle by viewModel.activeFilterTitle.collectAsStateWithLifecycle()
    val activePlaylistId by viewModel.activePlaylistId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val inspectedDetails by viewModel.inspectedTrackDetails.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var trackForPlaylistPicker by remember { mutableStateOf<AudioTrackItem?>(null) }

    // Лаунчер системного подтверждения удаления файла (MediaStore.createDeleteRequest)
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            viewModel.rescanLibrary()
        }
    }

    // Лаунчер импорта .m3u
    val importM3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
            viewModel.importM3uPlaylist("Imported M3U", content)
        }
    }

    // Лаунчер экспорта .m3u
    var playlistToExport by remember { mutableStateOf<PlaylistSummary?>(null) }
    val exportM3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val target = playlistToExport ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val m3u = viewModel.exportPlaylistM3u(target.id, target.name)
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(m3u.toByteArray()) }
            }
        }
    }

    val playlistTracksFlow = remember(activePlaylistId) {
        activePlaylistId?.let { viewModel.libraryRepo.observePlaylistTracks(it) }
    }
    val activePlaylistTracks by (playlistTracksFlow?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?: remember { mutableStateOf(emptyList()) })

    val displayedTracks = if (selectedSection == LibrarySection.PLAYLISTS && activePlaylistId != null) {
        activePlaylistTracks
    } else {
        visibleTracks
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. Шапка + кнопки очереди, таймера сна, сортировки и обновления
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = TextWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.by_author_fmt, AppInfo.AUTHOR_NAME),
                        color = AccentMint,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = if (hasAudioPermission) {
                        pluralStringResource(R.plurals.tracks_count, displayedTracks.size, displayedTracks.size) + " • " +
                            stringResource(R.string.audio_output_fmt, playback.activeOutputDeviceName, playback.audioSessionId)
                    } else {
                        stringResource(R.string.app_subtitle)
                    },
                    color = TextMuted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (hasAudioPermission) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showSleepDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = stringResource(R.string.sleep_timer_btn),
                            tint = if (playback.sleepTimerRemainingSec > 0 || playback.sleepStopAtEndOfTrack) AccentMint else TextMuted
                        )
                    }
                    IconButton(onClick = { showQueueSheet = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = stringResource(R.string.cd_queue),
                            tint = AccentMint
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = stringResource(R.string.sort_by),
                                tint = TextWhite
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            listOf(
                                SortOption.TITLE_ASC to R.string.sort_title,
                                SortOption.ARTIST_ASC to R.string.sort_artist,
                                SortOption.ALBUM_ASC to R.string.sort_album,
                                SortOption.DATE_ADDED_DESC to R.string.sort_date,
                                SortOption.DURATION_DESC to R.string.sort_duration,
                                SortOption.YEAR_DESC to R.string.sort_year
                            ).forEach { (opt, labelRes) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(labelRes)) },
                                    onClick = {
                                        viewModel.setSortOption(opt)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.rescanLibrary() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.btn_rescan),
                            tint = AccentMint
                        )
                    }
                }
            }
        }

        if (hasAudioPermission) {
            // 2. Поле мгновенного поиска без учёта регистра и диакритики
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text(stringResource(R.string.search_hint), color = TextMuted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentMint) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = TextMuted)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = AccentMint,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // 3. Горизонтальная лента разделов библиотеки
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    LibrarySection.TRACKS to R.string.sec_tracks,
                    LibrarySection.FAVORITES to R.string.sec_favorites,
                    LibrarySection.ALBUMS to R.string.sec_albums,
                    LibrarySection.ARTISTS to R.string.sec_artists,
                    LibrarySection.FOLDERS to R.string.sec_folders,
                    LibrarySection.PLAYLISTS to R.string.sec_playlists,
                    LibrarySection.GENRES to R.string.sec_genres,
                    LibrarySection.RECENTLY_ADDED to R.string.sec_recent_added,
                    LibrarySection.RECENTLY_PLAYED to R.string.sec_recent_played,
                    LibrarySection.MOST_PLAYED to R.string.sec_most_played
                ).forEach { (sec, titleRes) ->
                    val selected = selectedSection == sec
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.selectSection(sec) },
                        label = {
                            Text(
                                text = stringResource(titleRes),
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentMint.copy(alpha = 0.25f),
                            selectedLabelColor = AccentMint,
                            labelColor = TextMuted
                        )
                    )
                }
            }

            // Хлебные крошки, если открыт конкретный Альбом / Исполнитель / Папка / Плейлист
            if (activeFilterTitle != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { viewModel.clearSubFilter() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = AccentMint)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.back_to_list), color = AccentMint)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = activeFilterTitle.orEmpty(),
                        color = TextWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 4. Основная область списка
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                !hasAudioPermission -> {
                    PermissionPromptCard(
                        onGrant = onRequestPermission,
                        onOpenSettings = onOpenAppSettings
                    )
                }
                isScanning && displayedTracks.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AccentMint)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.library_scanning), color = TextMuted)
                        }
                    }
                }
                // Списки групп (Альбомы, Исполнители, Жанры, Папки, Плейлисты), когда подфильтр ещё не выбран
                selectedSection == LibrarySection.ALBUMS && activeFilterTitle == null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(albums, key = { _, a -> "${a.albumId}_${a.title}" }) { _, album ->
                            GroupSummaryCard(
                                title = album.title,
                                subtitle = "${album.artist} • " + pluralStringResource(R.plurals.tracks_count, album.trackCount, album.trackCount),
                                onClick = { viewModel.openSubFilter(LibrarySection.ALBUMS, album.title) }
                            )
                        }
                    }
                }
                selectedSection == LibrarySection.ARTISTS && activeFilterTitle == null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(artists, key = { _, a -> a.name }) { _, artist ->
                            GroupSummaryCard(
                                title = artist.name,
                                subtitle = pluralStringResource(R.plurals.tracks_count, artist.trackCount, artist.trackCount),
                                onClick = { viewModel.openSubFilter(LibrarySection.ARTISTS, artist.name) }
                            )
                        }
                    }
                }
                selectedSection == LibrarySection.GENRES && activeFilterTitle == null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(genres, key = { _, g -> g.name }) { _, genre ->
                            GroupSummaryCard(
                                title = genre.name,
                                subtitle = pluralStringResource(R.plurals.tracks_count, genre.trackCount, genre.trackCount),
                                onClick = { viewModel.openSubFilter(LibrarySection.GENRES, genre.name) }
                            )
                        }
                    }
                }
                selectedSection == LibrarySection.FOLDERS && activeFilterTitle == null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(folders, key = { _, f -> f.path }) { _, folder ->
                            GroupSummaryCard(
                                title = folder.name,
                                subtitle = "${folder.path} • " + pluralStringResource(R.plurals.tracks_count, folder.trackCount, folder.trackCount),
                                onClick = { viewModel.openSubFilter(LibrarySection.FOLDERS, folder.path) }
                            )
                        }
                    }
                }
                selectedSection == LibrarySection.PLAYLISTS && activePlaylistId == null -> {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showNewPlaylistDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentMint, contentColor = BgDeep),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.playlist_new_btn), fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { importM3uLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "*/*")) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.playlist_import_m3u), color = TextWhite)
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(playlists, key = { _, p -> p.id }) { _, pl ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(GlassSurface)
                                        .clickable {
                                            viewModel.openSubFilter(LibrarySection.PLAYLISTS, pl.name, pl.id)
                                        }
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pl.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text(
                                            pluralStringResource(R.plurals.tracks_count, pl.trackCount, pl.trackCount),
                                            color = TextMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Row {
                                        IconButton(onClick = {
                                            playlistToExport = pl
                                            exportM3uLauncher.launch("${pl.name}.m3u8")
                                        }) {
                                            Icon(Icons.Default.SaveAlt, contentDescription = stringResource(R.string.playlist_export_m3u), tint = AccentMint)
                                        }
                                        IconButton(onClick = { viewModel.deletePlaylist(pl.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF4D6D))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                displayedTracks.isEmpty() -> {
                    EmptyLibraryCard(onRescan = { viewModel.rescanLibrary() })
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = displayedTracks,
                            key = { _, item -> item.id },
                            contentType = { _, _ -> "track_row" }
                        ) { index, track ->
                            val isCurrent = playback.currentMediaId == track.id.toString()
                            TrackGlassRow(
                                track = track,
                                searchQuery = searchQuery,
                                isSelected = isCurrent,
                                isPlaying = isCurrent && playback.isPlaying,
                                onClick = { viewModel.playFromCurrentList(displayedTracks, index) },
                                onToggleFavorite = { viewModel.toggleFavorite(track.id) },
                                onPlayNext = { viewModel.playNext(track) },
                                onAddToQueue = { viewModel.addToQueue(track) },
                                onAddToPlaylist = { trackForPlaylistPicker = track },
                                onShowInfo = { viewModel.inspectTrack(track) },
                                onShareFile = {
                                    runCatching {
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = track.mimeType.ifEmpty { "audio/*" }
                                            putExtra(Intent.EXTRA_STREAM, track.contentUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, track.title))
                                    }
                                },
                                onDeleteFromDevice = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        runCatching {
                                            val pi = MediaStore.createDeleteRequest(
                                                context.contentResolver,
                                                listOf(track.contentUri)
                                            )
                                            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Запас высоты под парящий MiniPlayerPill и нижнюю панель с линзой
        Spacer(modifier = Modifier.height(148.dp))
    }

    // Диалог создания плейлиста
    if (showNewPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showNewPlaylistDialog = false },
            title = { Text(stringResource(R.string.playlist_new_btn)) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text(stringResource(R.string.playlist_name_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createPlaylist(newPlaylistName)
                    newPlaylistName = ""
                    showNewPlaylistDialog = false
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylistDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Диалог добавления трека в плейлист
    trackForPlaylistPicker?.let { targetTrack ->
        AlertDialog(
            onDismissRequest = { trackForPlaylistPicker = null },
            title = { Text(stringResource(R.string.action_add_playlist)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (playlists.isEmpty()) {
                        Text(stringResource(R.string.playlist_new_btn))
                    }
                    playlists.forEach { pl ->
                        TextButton(
                            onClick = {
                                viewModel.addTrackToPlaylist(pl.id, targetTrack.id)
                                trackForPlaylistPicker = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${pl.name} (${pl.trackCount})")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    trackForPlaylistPicker = null
                    showNewPlaylistDialog = true
                }) {
                    Text(stringResource(R.string.playlist_new_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { trackForPlaylistPicker = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Диалог сведений о файле и тегах (формат, битрейт, частота, ReplayGain, путь)
    inspectedDetails?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissTrackDetails() },
            title = { Text(info.trackTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Format: ${info.formatMime}")
                    Text("Bitrate: ${info.bitrateKbps} kbps")
                    Text("Sample Rate: ${info.sampleRateHz} Hz (${info.channelsCount} ch)")
                    Text("Size: ${info.fileSizeMb}")
                    Text("ReplayGain: ${info.replayGainDb?.let { "${"%.2f".format(it)} dB" } ?: "None"}")
                    Text("Path: ${info.filePath}", fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissTrackDetails() }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    // Диалог таймера сна
    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text(stringResource(R.string.sleep_timer_btn)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        0 to R.string.sleep_off,
                        15 to R.string.sleep_15m,
                        30 to R.string.sleep_30m,
                        60 to R.string.sleep_60m
                    ).forEach { (mins, resId) ->
                        TextButton(
                            onClick = {
                                viewModel.startSleepTimer(mins, stopAtEnd = false)
                                showSleepDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(resId))
                        }
                    }
                    TextButton(
                        onClick = {
                            viewModel.startSleepTimer(0, stopAtEnd = true)
                            showSleepDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.sleep_end_of_track))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Модальное окно очереди воспроизведения (перестановка вверх/вниз и удаление)
    if (showQueueSheet) {
        AlertDialog(
            onDismissRequest = { showQueueSheet = false },
            title = { Text(stringResource(R.string.queue_title_fmt, playback.queueEntries.size)) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = playback.queueEntries,
                        key = { idx, item -> "${idx}_${item.mediaId}" }
                    ) { idx, qItem ->
                        val isCurrent = idx == playback.currentQueueIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isCurrent) AccentMint.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { viewModel.skipToQueueItem(idx) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${idx + 1}. ${qItem.title}",
                                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = qItem.artist,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (idx > 0) {
                                IconButton(
                                    onClick = { viewModel.moveQueueItem(idx, idx - 1) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                                }
                            }
                            if (idx < playback.queueEntries.lastIndex) {
                                IconButton(
                                    onClick = { viewModel.moveQueueItem(idx, idx + 1) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                                }
                            }
                            IconButton(
                                onClick = { viewModel.removeQueueItem(idx) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQueueSheet = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
}

@Composable
private fun GroupSummaryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GlassSurface)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AccentMint)
    }
}

@Composable
private fun TrackGlassRow(
    track: AudioTrackItem,
    searchQuery: String,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShowInfo: () -> Unit,
    onShareFile: () -> Unit,
    onDeleteFromDevice: () -> Unit
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    var menuExpanded by remember { mutableStateOf(false) }

    val highlightedTitle = remember(track.title, searchQuery) {
        TextSearchUtils.highlightQuery(track.title, searchQuery, AccentMint)
    }
    val subtitleRaw = "${track.artist} • ${track.album}"
    val highlightedSubtitle = remember(subtitleRaw, searchQuery) {
        TextSearchUtils.highlightQuery(subtitleRaw, searchQuery, AccentMint)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (isSelected) AccentMint.copy(alpha = 0.16f) else GlassSurface)
            .border(
                width = if (isSelected) 1.4.dp else 0.8.dp,
                color = if (isSelected) AccentMint else Color.White.copy(alpha = 0.12f),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.MusicNote,
                contentDescription = null,
                tint = if (isSelected) AccentMint else TextMuted,
                modifier = Modifier.size(22.dp)
            )
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(track.resolveBestArtworkModel())
                    .size(144)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(R.string.cd_album_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightedTitle,
                color = if (isSelected) AccentMint else TextWhite,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = highlightedSubtitle,
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = TimeFormatUtils.formatDurationMs(track.durationMs),
            color = TextMuted,
            fontSize = 11.5.sp
        )

        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = stringResource(R.string.cd_favorite),
                tint = if (track.isFavorite) Color(0xFFFF4D6D) else TextMuted,
                modifier = Modifier.size(19.dp)
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = null, tint = TextMuted)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_play_next)) },
                    onClick = { menuExpanded = false; onPlayNext() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_add_queue)) },
                    onClick = { menuExpanded = false; onAddToQueue() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_add_playlist)) },
                    onClick = { menuExpanded = false; onAddToPlaylist() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_file_info)) },
                    onClick = { menuExpanded = false; onShowInfo() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share_file)) },
                    onClick = { menuExpanded = false; onShareFile() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete_device)) },
                    onClick = { menuExpanded = false; onDeleteFromDevice() }
                )
            }
        }
    }
}

@Composable
private fun NowPlayingBottomDock(
    playback: io.github.mrquster.hyperplay.audio.PlaybackUiState,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onCycleSpeed: () -> Unit
) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val duration = playback.durationMs.coerceAtLeast(1L)
    val progress = (playback.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xE60B1222))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            text = playback.currentTitle.ifEmpty { stringResource(R.string.now_playing_idle) },
            color = TextWhite,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (playback.currentArtist.isNotEmpty()) {
            Text(
                text = "${playback.currentArtist} • ${playback.currentAlbum}",
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Slider(
            value = progress,
            onValueChange = { frac ->
                onSeek((frac * duration).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = AccentMint,
                activeTrackColor = AccentMint,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = TimeFormatUtils.formatDurationMs(playback.positionMs),
                color = TextMuted,
                fontSize = 11.sp
            )
            if (playback.sleepTimerRemainingSec > 0) {
                Text(
                    text = "⏱ ${TimeFormatUtils.formatDurationMs(playback.sleepTimerRemainingSec * 1000L)}",
                    color = AccentMint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = TimeFormatUtils.formatDurationMs(playback.durationMs),
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = stringResource(R.string.cd_shuffle),
                    tint = if (playback.shuffleEnabled) AccentMint else TextMuted
                )
            }

            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.cd_previous),
                    tint = TextWhite,
                    modifier = Modifier.size(30.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AccentMint, AccentCyan)))
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play_pause),
                    tint = BgDeep,
                    modifier = Modifier.size(30.dp)
                )
            }

            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = stringResource(R.string.cd_next),
                    tint = TextWhite,
                    modifier = Modifier.size(30.dp)
                )
            }

            IconButton(onClick = onCycleRepeat) {
                val repeatIcon = when (playback.repeatMode) {
                    Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                }
                Icon(
                    imageVector = repeatIcon,
                    contentDescription = stringResource(R.string.cd_repeat),
                    tint = if (playback.repeatMode != Player.REPEAT_MODE_OFF) AccentMint else TextMuted
                )
            }

            TextButton(onClick = onCycleSpeed) {
                Text(
                    text = stringResource(R.string.speed_fmt, "%.2f".format(playback.playbackSpeed).trimEnd('0').trimEnd('.')),
                    color = AccentMint,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PermissionPromptCard(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(GlassSurface)
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(26.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = AccentMint,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = stringResource(R.string.perm_audio_title),
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                text = stringResource(R.string.perm_audio_desc),
                color = TextMuted,
                fontSize = 13.5.sp
            )
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = AccentMint, contentColor = BgDeep),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.perm_grant_btn), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.perm_open_settings_btn), color = TextWhite)
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(onRescan: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(GlassSurface)
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(26.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = AccentMint,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = stringResource(R.string.library_empty_title),
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Text(
                text = stringResource(R.string.library_empty_desc),
                color = TextMuted,
                fontSize = 13.sp
            )
            Button(
                onClick = onRescan,
                colors = ButtonDefaults.buttonColors(containerColor = AccentMint, contentColor = BgDeep)
            ) {
                Text(stringResource(R.string.btn_rescan), fontWeight = FontWeight.Bold)
            }
        }
    }
}
