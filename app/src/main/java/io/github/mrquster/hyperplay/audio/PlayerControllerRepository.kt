package io.github.mrquster.hyperplay.audio

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.mrquster.hyperplay.data.AudioTrackItem
import io.github.mrquster.hyperplay.data.CoverArtAndTagReader
import io.github.mrquster.hyperplay.data.LibraryRepository
import io.github.mrquster.hyperplay.data.PlaybackPreferencesRepository
import io.github.mrquster.hyperplay.data.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow

@Immutable
data class QueueEntryUi(
    val indexInQueue: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUri: Uri?
)

@Immutable
data class PlaybackUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val currentMediaId: String = "",
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentAlbum: String = "",
    val currentArtworkUri: Uri? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val playbackSpeed: Float = 1.0f,
    val activeOutputDeviceName: String = "Speaker",
    val audioSessionId: Int = 0,
    val queueEntries: List<QueueEntryUi> = emptyList(),
    val currentQueueIndex: Int = 0,
    val sleepTimerRemainingSec: Int = 0,
    val sleepStopAtEndOfTrack: Boolean = false
)

class PlayerControllerRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val libraryRepository: LibraryRepository,
    private val prefsRepository: PlaybackPreferencesRepository
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var controller: MediaController? = null
    private var positionTickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var fadeJob: Job? = null
    private var queueRestoredOnce = false
    private var lastObservedOutputName = ""

    @Volatile
    private var cachedSettings = PlaybackSettings()

    @Volatile
    private var baseReplayGainVolume = 1.0f

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState = _playbackState.asStateFlow()

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFastStateFromController()
            if (isPlaying) {
                startPositionTicker()
            } else {
                stopPositionTicker()
                persistPositionOnlyAsync()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFastStateFromController()
            persistPositionOnlyAsync()

            val mediaIdLong = mediaItem?.mediaId?.toLongOrNull()
            if (mediaIdLong != null) {
                scope.launch(Dispatchers.IO) {
                    libraryRepository.recordTrackPlayed(mediaIdLong)
                }
            }

            if (_playbackState.value.sleepStopAtEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _playbackState.value = _playbackState.value.copy(
                    sleepStopAtEndOfTrack = false,
                    sleepTimerRemainingSec = 0
                )
                return
            }

            applyReplayGainWithoutVolumeHole(mediaItem)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            rebuildQueueListFromController()
            syncFastStateFromController()
            persistFullQueueAsync()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncFastStateFromController()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            syncFastStateFromController()
            persistPositionOnlyAsync()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncFastStateFromController()
            persistPositionOnlyAsync()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            syncFastStateFromController()
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            updateOutputDeviceName()
            val hasBtAdded = addedDevices?.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } == true
            if (hasBtAdded && cachedSettings.autoResumeOnBluetooth) {
                val ctrl = controller
                if (ctrl != null && !ctrl.isPlaying && ctrl.mediaItemCount > 0) {
                    PlaybackEventLogger.logEvent("BT_AUTORESUME", "Auto-resuming playback on Bluetooth connect")
                    ctrl.play()
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            updateOutputDeviceName()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        updateOutputDeviceName()

        scope.launch {
            prefsRepository.settingsFlow.collect { latest ->
                cachedSettings = latest
            }
        }

        scope.launch {
            HyperPlayPlaybackService.activeAudioSessionId.collect { sessionId ->
                _playbackState.value = _playbackState.value.copy(audioSessionId = sessionId)
            }
        }
    }

    fun connectIfNeeded() {
        if (controller != null) return
        scope.launch {
            runCatching {
                val token = SessionToken(
                    context,
                    ComponentName(context, HyperPlayPlaybackService::class.java)
                )
                val built = MediaController.Builder(context, token).buildAsync().await()
                built.addListener(controllerListener)
                controller = built
                rebuildQueueListFromController()
                syncFastStateFromController()
                if (built.isPlaying) {
                    startPositionTicker()
                } else if (built.mediaItemCount == 0 && !queueRestoredOnce) {
                    restoreSavedQueueIfPossible()
                }
            }
        }
    }

    suspend fun restoreSavedQueueIfPossible() {
        if (queueRestoredOnce) return
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount > 0) {
            queueRestoredOnce = true
            return
        }
        val settings = prefsRepository.settingsFlow.first()
        if (!settings.rememberQueueAndPosition) return

        val snapshot = prefsRepository.loadSavedQueueSnapshot()
        if (snapshot.trackIds.isEmpty()) return

        val allTracks = libraryRepository.allTracksFlow.first()
        if (allTracks.isEmpty()) return

        queueRestoredOnce = true
        val trackMap = allTracks.associateBy { it.id }
        val restoredTracks = snapshot.trackIds.mapNotNull { trackMap[it] }
        if (restoredTracks.isEmpty()) return

        val mediaItems = restoredTracks.map { it.toMedia3Item() }
        val targetIndex = restoredTracks.indexOfFirst { it.id == snapshot.currentTrackId }.coerceAtLeast(0)
        ctrl.setMediaItems(mediaItems, targetIndex, snapshot.currentPositionMs.coerceAtLeast(0L))
        ctrl.shuffleModeEnabled = snapshot.shuffleEnabled
        ctrl.repeatMode = snapshot.repeatMode
        ctrl.prepare()
        rebuildQueueListFromController()
        syncFastStateFromController()
    }

    fun playQueueFromIndex(tracks: List<AudioTrackItem>, startIndex: Int) {
        val ctrl = controller ?: return
        if (tracks.isEmpty()) return
        queueRestoredOnce = true
        PlaybackEventLogger.userIntendedPlay = true
        val mediaItems = tracks.map { it.toMedia3Item() }
        val safeIndex = startIndex.coerceIn(0, mediaItems.lastIndex)
        ctrl.setMediaItems(mediaItems, safeIndex, 0L)
        ctrl.volume = baseReplayGainVolume
        ctrl.prepare()
        ctrl.play()
        rebuildQueueListFromController()
        syncFastStateFromController()
        persistFullQueueAsync()
    }

    fun playNextInQueue(track: AudioTrackItem) {
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) {
            playQueueFromIndex(listOf(track), 0)
            return
        }
        val insertIndex = (ctrl.currentMediaItemIndex + 1).coerceAtMost(ctrl.mediaItemCount)
        ctrl.addMediaItem(insertIndex, track.toMedia3Item())
        rebuildQueueListFromController()
        syncFastStateFromController()
        persistFullQueueAsync()
    }

    fun addToEndOfQueue(track: AudioTrackItem) {
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) {
            playQueueFromIndex(listOf(track), 0)
            return
        }
        ctrl.addMediaItem(track.toMedia3Item())
        rebuildQueueListFromController()
        syncFastStateFromController()
        persistFullQueueAsync()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val ctrl = controller ?: return
        if (fromIndex in 0 until ctrl.mediaItemCount && toIndex in 0 until ctrl.mediaItemCount) {
            ctrl.moveMediaItem(fromIndex, toIndex)
            rebuildQueueListFromController()
            syncFastStateFromController()
            persistFullQueueAsync()
        }
    }

    fun removeQueueItemAt(index: Int) {
        val ctrl = controller ?: return
        if (index in 0 until ctrl.mediaItemCount) {
            ctrl.removeMediaItem(index)
            rebuildQueueListFromController()
            syncFastStateFromController()
            persistFullQueueAsync()
        }
    }

    fun skipToQueueIndex(index: Int) {
        val ctrl = controller ?: return
        if (index in 0 until ctrl.mediaItemCount) {
            PlaybackEventLogger.userIntendedPlay = true
            ctrl.volume = baseReplayGainVolume
            ctrl.seekToDefaultPosition(index)
            ctrl.play()
            syncFastStateFromController()
        }
    }

    fun playExternalUri(uri: Uri) {
        val ctrl = controller ?: return
        PlaybackEventLogger.userIntendedPlay = true
        val item = MediaItem.fromUri(uri)
        ctrl.setMediaItem(item)
        ctrl.volume = 1.0f
        ctrl.prepare()
        ctrl.play()
        rebuildQueueListFromController()
        syncFastStateFromController()
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) {
            PlaybackEventLogger.userIntendedPlay = false
            ctrl.pause()
        } else {
            PlaybackEventLogger.userIntendedPlay = true
            if (ctrl.playbackState == Player.STATE_IDLE) ctrl.prepare()
            ctrl.volume = baseReplayGainVolume
            ctrl.play()
        }
    }

    fun seekToNext() {
        val ctrl = controller ?: return
        ctrl.volume = baseReplayGainVolume
        ctrl.seekToNext()
        syncFastStateFromController()
    }

    fun seekToPrevious() {
        val ctrl = controller ?: return
        ctrl.volume = baseReplayGainVolume
        ctrl.seekToPrevious()
        syncFastStateFromController()
    }

    fun seekToPositionMs(positionMs: Long) {
        val ctrl = controller ?: return
        ctrl.seekTo(positionMs.coerceAtLeast(0L))
        _playbackState.value = _playbackState.value.copy(positionMs = ctrl.currentPosition.coerceAtLeast(0L))
        persistPositionOnlyAsync()
    }

    fun toggleShuffle() {
        val ctrl = controller ?: return
        ctrl.shuffleModeEnabled = !ctrl.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        val ctrl = controller ?: return
        ctrl.repeatMode = when (ctrl.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val ctrl = controller ?: return
        ctrl.setPlaybackSpeed(speed.coerceIn(0.5f, 2.0f))
    }

    fun startSleepTimer(minutes: Int, stopAtEndOfCurrentTrack: Boolean = false) {
        sleepTimerJob?.cancel()
        if (stopAtEndOfCurrentTrack) {
            _playbackState.value = _playbackState.value.copy(
                sleepStopAtEndOfTrack = true,
                sleepTimerRemainingSec = 0
            )
            return
        }
        if (minutes <= 0) {
            controller?.volume = baseReplayGainVolume
            _playbackState.value = _playbackState.value.copy(
                sleepStopAtEndOfTrack = false,
                sleepTimerRemainingSec = 0
            )
            return
        }

        _playbackState.value = _playbackState.value.copy(sleepStopAtEndOfTrack = false)
        sleepTimerJob = scope.launch {
            var remaining = minutes * 60
            while (remaining > 0 && isActive) {
                _playbackState.value = _playbackState.value.copy(sleepTimerRemainingSec = remaining)
                if (remaining in 1..5) {
                    val fadeRatio = remaining / 5f
                    controller?.volume = (baseReplayGainVolume * fadeRatio).coerceIn(0.05f, 1f)
                }
                delay(1000L)
                remaining--
            }
            if (remaining <= 0) {
                PlaybackEventLogger.userIntendedPlay = false
                controller?.pause()
                controller?.volume = baseReplayGainVolume
                _playbackState.value = _playbackState.value.copy(sleepTimerRemainingSec = 0)
            }
        }
    }

    /**
     * Применяет ReplayGain без создания секундного провала громкости на стыке треков.
     */
    private fun applyReplayGainWithoutVolumeHole(mediaItem: MediaItem?) {
        fadeJob?.cancel()
        val ctrl = controller ?: return
        val settings = cachedSettings
        val ab = AudioDiagnosticsEngine.abSwitches.value
        val useRg = !ab.pureBypassAll && (settings.replayGainEnabled || ab.replayGainEnabled)

        if (!useRg) {
            baseReplayGainVolume = 1.0f
            ctrl.volume = 1.0f
            return
        }

        scope.launch {
            val filePath = mediaItem?.mediaMetadata?.extras?.getString("filePath").orEmpty()
            val rgDb = if (filePath.isNotEmpty()) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    CoverArtAndTagReader.parseReplayGainTagFromFile(filePath)
                } ?: 0f
            } else 0f

            val targetVolume = if (rgDb != 0f) {
                10.0.pow(rgDb.coerceIn(-18f, 3.0f) / 20.0).toFloat().coerceIn(0.25f, 0.96f)
            } else 1.0f

            baseReplayGainVolume = targetVolume
            ctrl.volume = targetVolume
        }
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            var tickCounter = 0
            while (isActive) {
                val ctrl = controller
                if (ctrl != null && ctrl.isPlaying) {
                    val pos = ctrl.currentPosition.coerceAtLeast(0L)
                    val dur = ctrl.duration.coerceAtLeast(0L)
                    _playbackState.value = _playbackState.value.copy(
                        positionMs = pos,
                        durationMs = dur
                    )

                    tickCounter++
                    // Сохраняем ТОЛЬКО 2 числа (ID трека и позицию) раз в 10 секунд (40 тиков * 250 мс) без обхода очереди!
                    if (tickCounter % 40 == 0) {
                        persistPositionOnlyAsync()
                    }
                }
                delay(250L)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun persistPositionOnlyAsync() {
        val ctrl = controller ?: return
        if (!cachedSettings.rememberQueueAndPosition || ctrl.mediaItemCount == 0) return
        val currId = ctrl.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        val pos = ctrl.currentPosition.coerceAtLeast(0L)
        val shuf = ctrl.shuffleModeEnabled
        val rep = ctrl.repeatMode

        scope.launch(Dispatchers.IO) {
            prefsRepository.savePositionOnlySnapshot(currId, pos, shuf, rep)
        }
    }

    private fun persistFullQueueAsync() {
        val ctrl = controller ?: return
        if (!cachedSettings.rememberQueueAndPosition || ctrl.mediaItemCount == 0) return

        val count = ctrl.mediaItemCount.coerceAtMost(2500)
        val ids = ArrayList<Long>(count)
        for (i in 0 until count) {
            ctrl.getMediaItemAt(i).mediaId.toLongOrNull()?.let { ids.add(it) }
        }
        val currId = ctrl.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        val pos = ctrl.currentPosition.coerceAtLeast(0L)
        val shuf = ctrl.shuffleModeEnabled
        val rep = ctrl.repeatMode

        scope.launch(Dispatchers.IO) {
            prefsRepository.saveQueueSnapshot(ids, currId, pos, shuf, rep)
        }
    }

    /**
     * Вызывается ТОЛЬКО при изменении очереди (onTimelineChanged), а не каждые 250 мс!
     */
    private fun rebuildQueueListFromController() {
        val ctrl = controller ?: return
        val count = ctrl.mediaItemCount.coerceAtMost(1000)
        val queueList = ArrayList<QueueEntryUi>(count)
        for (i in 0 until count) {
            val item = ctrl.getMediaItemAt(i)
            val m = item.mediaMetadata
            queueList.add(
                QueueEntryUi(
                    indexInQueue = i,
                    mediaId = item.mediaId,
                    title = m.title?.toString().orEmpty(),
                    artist = m.artist?.toString().orEmpty(),
                    artworkUri = m.artworkUri
                )
            )
        }
        _playbackState.value = _playbackState.value.copy(queueEntries = queueList)
    }

    /**
     * Мгновенная синхронизация метаданных текущего трека (< 0.1 мс на Main Thread)
     */
    private fun syncFastStateFromController() {
        val ctrl = controller ?: return
        val currentItem = ctrl.currentMediaItem
        val meta = currentItem?.mediaMetadata

        _playbackState.value = _playbackState.value.copy(
            isConnected = true,
            isPlaying = ctrl.isPlaying,
            currentMediaId = currentItem?.mediaId.orEmpty(),
            currentTitle = meta?.title?.toString().orEmpty(),
            currentArtist = meta?.artist?.toString().orEmpty(),
            currentAlbum = meta?.albumTitle?.toString().orEmpty(),
            currentArtworkUri = meta?.artworkUri,
            positionMs = ctrl.currentPosition.coerceAtLeast(0L),
            durationMs = ctrl.duration.coerceAtLeast(0L),
            shuffleEnabled = ctrl.shuffleModeEnabled,
            repeatMode = ctrl.repeatMode,
            playbackSpeed = ctrl.playbackParameters.speed,
            currentQueueIndex = ctrl.currentMediaItemIndex.coerceAtLeast(0)
        )
    }

    private fun updateOutputDeviceName() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        val usb = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        val wired = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        val outName = when {
            bt != null -> bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            usb != null -> usb.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB DAC"
            wired != null -> "Wired Headphones"
            else -> "Speaker"
        }

        if (outName != lastObservedOutputName) {
            val prev = lastObservedOutputName
            lastObservedOutputName = outName
            PlaybackEventLogger.currentOutputLabel = outName
            if (prev.isNotEmpty()) {
                PlaybackEventLogger.logEvent("ROUTE_CHANGE", "Audio output changed: $prev -> $outName")
                if (PlaybackEventLogger.isPlayerActivelyPlaying) {
                    PlaybackEventLogger.reportDropoutIncident(
                        reason = DropoutReasonCategory.ROUTE_CHANGE,
                        detail = "Output route switched from $prev to $outName during playback"
                    )
                }
            }
        }
        _playbackState.value = _playbackState.value.copy(activeOutputDeviceName = outName)
    }

    fun release() {
        stopPositionTicker()
        sleepTimerJob?.cancel()
        fadeJob?.cancel()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        controller?.removeListener(controllerListener)
        controller?.release()
        controller = null
    }
}
