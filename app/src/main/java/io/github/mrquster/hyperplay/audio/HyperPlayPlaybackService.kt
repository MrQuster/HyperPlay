package io.github.mrquster.hyperplay.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.mrquster.hyperplay.HyperPlayApp
import io.github.mrquster.hyperplay.MainActivity
import io.github.mrquster.hyperplay.island.HyperPlayMediaNotificationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class HyperPlayPlaybackService : MediaSessionService() {

    companion object {
        const val NOTIFICATION_ID = 4004
        const val CHANNEL_ID = "hyperplay_playback_channel"

        val dspAudioProcessor = HyperPlayDspAudioProcessor()

        private val _activeAudioSessionId = MutableStateFlow(C.AUDIO_SESSION_ID_UNSET)
        val activeAudioSessionId = _activeAudioSessionId.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var systemEventsReceiverRegistered = false

    private val systemPowerAndScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    PlaybackEventLogger.logEvent("SCREEN", "Screen turned OFF")
                }
                Intent.ACTION_SCREEN_ON -> {
                    PlaybackEventLogger.logEvent("SCREEN", "Screen turned ON")
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isSave = pm?.isPowerSaveMode == true
                    PlaybackEventLogger.logEvent("POWER", "Battery Saver mode changed: $isSave")
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isIdle = pm?.isDeviceIdleMode == true
                    PlaybackEventLogger.logEvent("DOZE", "Device Idle (Doze) mode changed: $isIdle")
                }
            }
        }
    }

    private val playerListener = object : Player.Listener {

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            _activeAudioSessionId.value = audioSessionId
            PlaybackEventLogger.logEvent("AUDIO_SESSION", "AudioSessionId changed to #$audioSessionId")
            (application as? HyperPlayApp)?.audioEffectsController?.bindToPlayerSession(audioSessionId)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "STATE_IDLE"
                Player.STATE_BUFFERING -> "STATE_BUFFERING"
                Player.STATE_READY -> "STATE_READY"
                Player.STATE_ENDED -> "STATE_ENDED"
                else -> "STATE_$playbackState"
            }
            val wasReadyAndPlaying = PlaybackEventLogger.isPlayerActivelyPlaying
            PlaybackEventLogger.isPlayerBuffering = (playbackState == Player.STATE_BUFFERING)
            PlaybackEventLogger.logEvent("STATE", "PlaybackState -> $stateName")

            // Если плеер играл и посреди трека внезапно ушёл в BUFFERING — фиксируем прерывание
            if (playbackState == Player.STATE_BUFFERING && wasReadyAndPlaying && PlaybackEventLogger.userIntendedPlay) {
                val p = player
                val pos = p?.currentPosition ?: 0L
                if (pos > 1500L) {
                    PlaybackEventLogger.reportDropoutIncident(
                        reason = DropoutReasonCategory.BUFFER_UNDERRUN,
                        detail = "Entered STATE_BUFFERING mid-track at ${pos}ms"
                    )
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val reasonName = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE_MEDIA_BUTTON"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
                else -> "REASON_$reason"
            }
            PlaybackEventLogger.logEvent("PLAY_WHEN_READY", "playWhenReady=$playWhenReady (reason=$reasonName)")

            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
            ) {
                PlaybackEventLogger.userIntendedPlay = playWhenReady
            }

            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                PlaybackEventLogger.reportDropoutIncident(
                    reason = DropoutReasonCategory.AUDIO_FOCUS,
                    detail = "Paused due to AUDIO_FOCUS_LOSS from system or another app"
                )
            } else if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                PlaybackEventLogger.reportDropoutIncident(
                    reason = DropoutReasonCategory.BECOMING_NOISY,
                    detail = "Paused due to AUDIO_BECOMING_NOISY (headphone/BT disconnect)"
                )
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            val supName = when (playbackSuppressionReason) {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "NONE (Normal)"
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "TRANSIENT_AUDIO_FOCUS_LOSS"
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> "UNSUITABLE_AUDIO_OUTPUT"
                else -> "SUPPRESSION_$playbackSuppressionReason"
            }
            PlaybackEventLogger.logEvent("SUPPRESSION", "SuppressionReason -> $supName")

            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                PlaybackEventLogger.reportDropoutIncident(
                    reason = DropoutReasonCategory.AUDIO_FOCUS,
                    detail = "Playback suppressed by TRANSIENT_AUDIO_FOCUS_LOSS (system notification/alert)"
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackEventLogger.isPlayerActivelyPlaying = isPlaying
            PlaybackEventLogger.logEvent("IS_PLAYING", "isPlaying=$isPlaying")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val rName = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO_GAPLESS"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "USER_SEEK"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                else -> "REASON_$reason"
            }
            val title = mediaItem?.mediaMetadata?.title?.toString().orEmpty()
            PlaybackEventLogger.currentTrackLabel = title
            PlaybackEventLogger.logEvent("TRACK_TRANSITION", "Transition ($rName) -> \"$title\"")

            // Применяем отложенный безопасный режим только на границе треков (без прерывания посреди песни!)
            AudioDiagnosticsEngine.applyPendingFallbackOnTrackBoundaryIfNeeded()
        }

        override fun onPlayerError(error: PlaybackException) {
            PlaybackEventLogger.reportDropoutIncident(
                reason = DropoutReasonCategory.PLAYER_ERROR,
                detail = "Error ${error.errorCodeName}: ${error.localizedMessage}"
            )
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedRealtimeMs: Long
        ) {
            AudioDiagnosticsEngine.onAudioUnderrunOccurred(bufferSize, bufferSizeMs, elapsedRealtimeMs)
            PlaybackEventLogger.reportDropoutIncident(
                reason = DropoutReasonCategory.BUFFER_UNDERRUN,
                detail = "AudioTrack underrun: buf=${bufferSize}B (${bufferSizeMs}ms), gap=${elapsedRealtimeMs}ms"
            )
        }

        override fun onAudioSinkError(
            eventTime: AnalyticsListener.EventTime,
            audioSinkError: Exception
        ) {
            PlaybackEventLogger.reportDropoutIncident(
                reason = DropoutReasonCategory.PLAYER_ERROR,
                detail = "AudioSinkError: ${audioSinkError.javaClass.simpleName} - ${audioSinkError.localizedMessage}"
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            AudioDiagnosticsEngine.onDecoderInitialized(decoderName)
            PlaybackEventLogger.logEvent("DECODER", "Initialized $decoderName in ${initializationDurationMs}ms")
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (loadEventInfo.loadDurationMs > 250L) {
                PlaybackEventLogger.logEvent(
                    "STORAGE_IO",
                    "Slow file chunk read: ${loadEventInfo.loadDurationMs}ms (${loadEventInfo.bytesLoaded} bytes)"
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlaybackEventLogger.logEvent("SERVICE", "HyperPlayPlaybackService onCreate()")
        registerSystemReceiverIfNeeded()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val abNow = AudioDiagnosticsEngine.abSwitches.value
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(abNow.pcmFloatOutputEnabled)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioProcessors(arrayOf(dspAudioProcessor))
                    .build()
            }
        }

        // Оптимизированный LoadControl для локальных файлов: быстрый старт (450 мс) + глубокий буфер (45-90 с)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 45_000,
                /* maxBufferMs = */ 90_000,
                /* bufferForPlaybackMs = */ 450,
                /* bufferForPlaybackAfterRebufferMs = */ 1_200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exoPlayer.addListener(playerListener)
        exoPlayer.addAnalyticsListener(analyticsListener)

        val initialSessionId = exoPlayer.audioSessionId
        _activeAudioSessionId.value = initialSessionId
        val app = application as? HyperPlayApp
        app?.audioEffectsController?.bindToPlayerSession(initialSessionId)

        player = exoPlayer

        if (app != null) {
            serviceScope.launch {
                combine(
                    app.playbackPrefs.settingsFlow,
                    AudioDiagnosticsEngine.abSwitches
                ) { prefs, ab ->
                    !ab.pureBypassAll && (prefs.skipSilenceEnabled || ab.skipSilenceEnabled)
                }.collect { effectiveSkipSilence ->
                    if (exoPlayer.skipSilenceEnabled != effectiveSkipSilence) {
                        exoPlayer.skipSilenceEnabled = effectiveSkipSilence
                        PlaybackEventLogger.logEvent("SKIP_SILENCE", "skipSilenceEnabled=$effectiveSkipSilence")
                    }
                }
            }
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            300,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(openAppIntent)
            .build()

        setMediaNotificationProvider(HyperPlayMediaNotificationProvider(this))
    }

    private fun registerSystemReceiverIfNeeded() {
        if (systemEventsReceiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
            registerReceiver(systemPowerAndScreenReceiver, filter)
            systemEventsReceiverRegistered = true
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        PlaybackEventLogger.logEvent("SERVICE", "onTaskRemoved called")
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        PlaybackEventLogger.logEvent("SERVICE", "HyperPlayPlaybackService onDestroy()")
        if (systemEventsReceiverRegistered) {
            runCatching { unregisterReceiver(systemPowerAndScreenReceiver) }
            systemEventsReceiverRegistered = false
        }
        AudioDiagnosticsEngine.stopReferenceSignal()
        (application as? HyperPlayApp)?.audioEffectsController?.releaseHardwareEffects()
        _activeAudioSessionId.value = C.AUDIO_SESSION_ID_UNSET
        serviceScope.cancel()
        this@HyperPlayPlaybackService.player?.removeAnalyticsListener(analyticsListener)
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
