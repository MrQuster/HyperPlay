package io.github.mrquster.hyperplay.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.mrquster.hyperplay.HyperPlayApp
import io.github.mrquster.hyperplay.R

class HyperPlayGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HyperPlayGlanceWidget()
}

class HyperPlayGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as? HyperPlayApp
        val playback = app?.playerRepository?.playbackState?.value

        provideContent {
            GlanceTheme {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ImageProvider(R.drawable.ic_launcher_background))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
                    ) {
                        Text(
                            text = playback?.currentTitle?.ifEmpty { "HyperPlay" } ?: "HyperPlay",
                            style = TextStyle(
                                color = ColorProvider(Color.White),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = playback?.currentArtist?.ifEmpty { "Local Audio Player" } ?: "Local Audio Player",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFFB8C4D9)),
                                fontSize = 12.sp
                            ),
                            maxLines = 1
                        )
                        Spacer(modifier = GlanceModifier.height(10.dp))
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                            verticalAlignment = Alignment.Vertical.CenterVertically
                        ) {
                            ButtonAction(iconRes = R.drawable.ic_stat_play, action = WidgetPrevAction::class.java)
                            Spacer(modifier = GlanceModifier.width(16.dp))
                            ButtonAction(iconRes = R.drawable.ic_stat_play, action = WidgetPlayPauseAction::class.java)
                            Spacer(modifier = GlanceModifier.width(16.dp))
                            ButtonAction(iconRes = R.drawable.ic_stat_play, action = WidgetNextAction::class.java)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonAction(iconRes: Int, action: Class<out ActionCallback>) {
    Box(
        modifier = GlanceModifier
            .size(42.dp)
            .background(ImageProvider(R.drawable.ic_launcher_background))
            .clickable(actionRunCallback(action)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp)
        )
    }
}

class WidgetPlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as? HyperPlayApp
        app?.playerRepository?.connectIfNeeded()
        app?.playerRepository?.togglePlayPause()
        HyperPlayGlanceWidget().update(context, glanceId)
    }
}

class WidgetNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as? HyperPlayApp
        app?.playerRepository?.connectIfNeeded()
        app?.playerRepository?.seekToNext()
        HyperPlayGlanceWidget().update(context, glanceId)
    }
}

class WidgetPrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as? HyperPlayApp
        app?.playerRepository?.connectIfNeeded()
        app?.playerRepository?.seekToPrevious()
        HyperPlayGlanceWidget().update(context, glanceId)
    }
}
