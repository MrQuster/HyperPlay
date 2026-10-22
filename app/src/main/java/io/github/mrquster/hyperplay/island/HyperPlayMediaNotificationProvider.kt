package io.github.mrquster.hyperplay.island

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import io.github.mrquster.hyperplay.HyperPlayApp
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.audio.HyperPlayPlaybackService
import io.github.mrquster.hyperplay.device.FeatureRegistry
import io.github.mrquster.hyperplay.device.IslandStyleOption
import io.github.mrquster.hyperplay.device.SelectedIslandStrategy
import java.util.concurrent.atomic.AtomicLong

@OptIn(UnstableApi::class)
class HyperPlayMediaNotificationProvider(
    private val context: Context
) : MediaNotification.Provider {

    companion object {
        private val sequenceCounter = AtomicLong(1L)
    }

    private val delegate = DefaultMediaNotificationProvider.Builder(context)
        .setChannelId(HyperPlayPlaybackService.CHANNEL_ID)
        .setChannelName(R.string.notif_channel_name)
        .setNotificationId(HyperPlayPlaybackService.NOTIFICATION_ID)
        .build()
        .apply {
            setSmallIcon(R.drawable.ic_stat_play)
        }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        val baseMediaNotif = delegate.createNotification(
            mediaSession,
            customLayout,
            actionFactory,
            onNotificationChangedCallback
        )

        val app = context.applicationContext as? HyperPlayApp ?: return baseMediaNotif
        val profile = app.deviceProfileFlow.value
        val plan = FeatureRegistry.resolvePlan(profile, app.islandPreferenceFlow.value, false)

        val player = mediaSession.player
        val meta = player.mediaMetadata
        val title = meta.title?.toString().orEmpty().ifEmpty { "HyperPlay" }
        val artist = meta.artist?.toString().orEmpty()
        val album = meta.albumTitle?.toString().orEmpty()
        val posMs = player.currentPosition.coerceAtLeast(0L)
        val durMs = player.duration.coerceAtLeast(1L)
        val progressPercent = ((posMs * 100L) / durMs).toInt().coerceIn(0, 100)
        val seqId = sequenceCounter.getAndIncrement()

        val notif = baseMediaNotif.notification
        notif.visibility = NotificationCompat.VISIBILITY_PUBLIC

        when (plan.selectedStrategy) {
            SelectedIslandStrategy.XIAOMI_SUPER_ISLAND -> {
                val snapshot = MusicIslandSnapshot(
                    sequenceNumber = seqId,
                    isPlaying = player.isPlaying,
                    trackTitle = title,
                    artistName = artist,
                    albumName = album,
                    positionMs = posMs,
                    durationMs = durMs,
                    progressPercent = progressPercent,
                    outputDeviceName = app.playerRepository.playbackState.value.activeOutputDeviceName,
                    accentHex = "#00F5D4",
                    styleOption = IslandStyleOption.COMPACT,
                    allowOnLockscreen = true
                )
                val validated = HyperPlayIslandJsonBuilder.attachFocusExtras(context, notif.extras, snapshot)
                IslandEventLogger.record(
                    seqId = seqId,
                    action = if (player.isPlaying) "MEDIA3_ISLAND_PLAY" else "MEDIA3_ISLAND_PAUSE",
                    strategyName = plan.selectedStrategy.name,
                    trackTitle = title,
                    progressPercent = progressPercent,
                    jsonSizeBytes = validated.sizeBytes,
                    isSuccess = validated.isValid,
                    detail = validated.note
                )
            }
            SelectedIslandStrategy.ANDROID_16_LIVE_UPDATE -> {
                val liveExtras = Bundle().apply {
                    putBoolean("android.requestPromotedOngoing", true)
                    putString("android.shortCriticalText", "$progressPercent%")
                }
                notif.extras.putAll(liveExtras)
                IslandEventLogger.record(
                    seqId = seqId,
                    action = "LIVE_UPDATE_SYNC",
                    strategyName = plan.selectedStrategy.name,
                    trackTitle = title,
                    progressPercent = progressPercent,
                    jsonSizeBytes = 96,
                    isSuccess = true,
                    detail = "Promoted ongoing extras attached"
                )
            }
            SelectedIslandStrategy.STANDARD_MEDIA3 -> {
                IslandEventLogger.record(
                    seqId = seqId,
                    action = "STANDARD_MEDIA3_SYNC",
                    strategyName = plan.selectedStrategy.name,
                    trackTitle = title,
                    progressPercent = progressPercent,
                    jsonSizeBytes = 0,
                    isSuccess = true,
                    detail = "Standard MediaStyle notification"
                )
            }
        }

        return baseMediaNotif
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        return delegate.handleCustomCommand(session, action, extras)
    }
}
