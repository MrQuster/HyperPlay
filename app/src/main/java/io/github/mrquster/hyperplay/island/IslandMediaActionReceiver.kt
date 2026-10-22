package io.github.mrquster.hyperplay.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mrquster.hyperplay.HyperPlayApp

class IslandMediaActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PREV = "io.github.mrquster.hyperplay.ACTION_ISLAND_PREV"
        const val ACTION_PLAY_PAUSE = "io.github.mrquster.hyperplay.ACTION_ISLAND_PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.mrquster.hyperplay.ACTION_ISLAND_NEXT"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? HyperPlayApp ?: return
        val repo = app.playerRepository
        repo.connectIfNeeded()

        val pb = repo.playbackState.value
        val dur = pb.durationMs.coerceAtLeast(1L)
        val prog = ((pb.positionMs * 100L) / dur).toInt().coerceIn(0, 100)

        IslandEventLogger.record(
            seqId = 0L,
            action = "ISLAND_CLICK:${action.substringAfterLast('_')}",
            strategyName = "BroadcastReceiver",
            trackTitle = pb.currentTitle,
            progressPercent = prog,
            jsonSizeBytes = 0,
            isSuccess = true,
            detail = "Command dispatched to MediaController"
        )

        when (action) {
            ACTION_PREV -> repo.seekToPrevious()
            ACTION_PLAY_PAUSE -> repo.togglePlayPause()
            ACTION_NEXT -> repo.seekToNext()
        }
    }
}
