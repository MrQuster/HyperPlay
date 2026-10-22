package io.github.mrquster.hyperplay.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.audio.AudioDiagnosticsEngine
import io.github.mrquster.hyperplay.audio.PlaybackEventLogger
import io.github.mrquster.hyperplay.data.database.LibraryDao
import io.github.mrquster.hyperplay.data.database.TrackEntity
import io.github.mrquster.hyperplay.util.TextSearchUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaStoreAudioScanner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val libraryDao: LibraryDao,
    private val prefsRepo: PlaybackPreferencesRepository
) {
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private var observerRegistered = false
    private var debounceJob: Job? = null

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(650L)
                // Не нагружаем флеш-память и SQLite, если прямо сейчас играет трек и база уже проиндексирована
                val ab = AudioDiagnosticsEngine.abSwitches.value
                val count = withContext(Dispatchers.IO) { libraryDao.getTracksCount() }
                if (count > 0 && (PlaybackEventLogger.isPlayerBuffering ||
                            (ab.pauseScannerDuringPlayback && PlaybackEventLogger.isPlayerActivelyPlaying))
                ) {
                    PlaybackEventLogger.logEvent("SCANNER_DEFERRED", "Deferred background MediaStore scan during active playback")
                    return@launch
                }
                triggerRescan()
            }
        }
    }

    fun registerObserverIfNeeded() {
        if (observerRegistered) return
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver
            )
            observerRegistered = true
        }
    }

    fun unregisterObserver() {
        if (!observerRegistered) return
        debounceJob?.cancel()
        runCatching {
            context.contentResolver.unregisterContentObserver(mediaObserver)
            observerRegistered = false
        }
    }

    fun triggerRescan() {
        if (_isScanning.value) return
        scope.launch(Dispatchers.IO) {
            _isScanning.value = true
            runCatching {
                val settings = prefsRepo.settingsFlow.first()
                val scannedEntities = scanMediaStoreToEntities(settings)
                libraryDao.syncScannedTracks(scannedEntities)
            }
            _isScanning.value = false
        }
    }

    private suspend fun scanMediaStoreToEntities(settings: PlaybackSettings): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<TrackEntity>(1024)
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val minDurationMs = settings.minTrackDurationSec * 1000L

            val baseProjection = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.IS_RINGTONE,
                MediaStore.Audio.Media.IS_NOTIFICATION,
                MediaStore.Audio.Media.IS_ALARM
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                baseProjection.add(MediaStore.Audio.Media.GENRE)
            }

            val selection = "${MediaStore.Audio.Media.DURATION} >= ? AND " +
                    "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
                    "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
                    "${MediaStore.Audio.Media.IS_ALARM} = 0"
            val selectionArgs = arrayOf(minDurationMs.toString())
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

            val unknownArtist = context.getString(R.string.unknown_artist)
            val unknownAlbum = context.getString(R.string.unknown_album)
            val unknownGenre = context.getString(R.string.unknown_genre)
            val artworkBaseUri = Uri.parse("content://media/external/audio/albumart")

            runCatching {
                context.contentResolver.query(
                    collection,
                    baseProjection.toTypedArray(),
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                    val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                    } else -1

                    while (cursor.moveToNext()) {
                        val filePath = cursor.getString(dataCol).orEmpty()
                        val parentFile = File(filePath).parentFile
                        val folderPath = parentFile?.absolutePath.orEmpty()
                        val folderName = parentFile?.name.orEmpty()

                        if (folderPath in settings.excludedFolderPaths) continue
                        if (settings.hideMessagingAndRingtoneFolders && isNoiseFolder(folderPath)) continue

                        val id = cursor.getLong(idCol)
                        val rawTitle = cursor.getString(titleCol)?.trim().orEmpty()
                        val rawArtist = cursor.getString(artistCol)?.trim().orEmpty()
                        val rawAlbum = cursor.getString(albumCol)?.trim().orEmpty()
                        val rawGenre = if (genreCol >= 0) cursor.getString(genreCol)?.trim().orEmpty() else ""

                        val albumId = cursor.getLong(albumIdCol)
                        val duration = cursor.getLong(durCol)
                        val trackNum = cursor.getInt(trackCol) % 1000
                        val year = cursor.getInt(yearCol)
                        val sizeBytes = cursor.getLong(sizeCol)
                        val dateAdded = cursor.getLong(dateCol)
                        val mime = cursor.getString(mimeCol).orEmpty()

                        val cleanArtist = if (rawArtist.isEmpty() || rawArtist == "<unknown>") unknownArtist else rawArtist
                        val cleanAlbum = if (rawAlbum.isEmpty() || rawAlbum == "<unknown>") unknownAlbum else rawAlbum
                        val cleanGenre = if (rawGenre.isEmpty() || rawGenre == "<unknown>") unknownGenre else rawGenre
                        val cleanTitle = rawTitle.ifEmpty { File(filePath).nameWithoutExtension.ifEmpty { "Track #$id" } }

                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val artUri = ContentUris.withAppendedId(artworkBaseUri, albumId)

                        val searchKey = TextSearchUtils.normalizeForSearch(
                            "$cleanTitle $cleanArtist $cleanAlbum $cleanGenre $folderName"
                        )

                        result.add(
                            TrackEntity(
                                id = id,
                                title = cleanTitle,
                                artist = cleanArtist,
                                album = cleanAlbum,
                                albumId = albumId,
                                genre = cleanGenre,
                                durationMs = duration,
                                trackNumber = trackNum,
                                year = year,
                                contentUriString = contentUri.toString(),
                                artworkUriString = artUri.toString(),
                                filePath = filePath,
                                folderPath = folderPath,
                                folderName = folderName,
                                sizeBytes = sizeBytes,
                                dateAddedSec = dateAdded,
                                mimeType = mime,
                                searchKey = searchKey
                            )
                        )
                    }
                }
            }
            result
        }

    private fun isNoiseFolder(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/whatsapp voice notes") ||
                lower.contains("/whatsapp audio") ||
                lower.contains("/telegram/telegram audio") ||
                lower.contains("/ringtones") ||
                lower.contains("/notifications") ||
                lower.contains("/alarms") ||
                lower.contains("/call_rec") ||
                lower.contains("/miui/sound_recorder")
    }
}
