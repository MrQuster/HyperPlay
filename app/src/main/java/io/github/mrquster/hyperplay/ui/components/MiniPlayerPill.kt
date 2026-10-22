package io.github.mrquster.hyperplay.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.audio.PlaybackUiState
import io.github.mrquster.hyperplay.ui.theme.LocalGlassTokens
import kotlin.math.abs

@Composable
fun MiniPlayerPill(
    playback: PlaybackUiState,
    artworkModel: Any?,
    hazeState: HazeState?,
    onOpenNowPlaying: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(28.dp)

    val duration = playback.durationMs.coerceAtLeast(1L)
    val progress = (playback.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    val supportsBackdropBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !tokens.powerSaveMode &&
            hazeState != null
    val translucentAlpha = (tokens.glassSurfaceAlpha * 2.3f).coerceIn(0.36f, 0.58f)

    val pillTint = when {
        tokens.isAmoled -> Color(0xFF050811).copy(alpha = if (supportsBackdropBlur) translucentAlpha else 0.86f)
        tokens.isDark -> Color(0xFF0B1326).copy(alpha = if (supportsBackdropBlur) translucentAlpha else 0.84f)
        else -> Color(0xFFF8FAFC).copy(alpha = if (supportsBackdropBlur) (translucentAlpha + 0.18f) else 0.90f)
    }

    val backdropMod = if (supportsBackdropBlur && hazeState != null) {
        Modifier.hazeChild(
            state = hazeState,
            style = HazeDefaults.style(
                backgroundColor = tokens.baseBackground,
                tint = HazeTint(color = pillTint),
                blurRadius = tokens.blurRadius.coerceIn(20.dp, 44.dp),
                noiseFactor = 0.03f
            )
        )
    } else {
        Modifier.background(pillTint)
    }

    var dragAccumX by remember { mutableFloatStateOf(0f) }
    var dragAccumY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .shadow(
                elevation = if (tokens.powerSaveMode) 6.dp else 16.dp,
                shape = shape,
                ambientColor = tokens.accentColor.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.50f)
            )
            .clip(shape)
            .then(backdropMod)
            .border(
                width = 1.1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.60f),
                        tokens.accentColor.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.14f)
                    )
                ),
                shape = shape
            )
            .clickable { onOpenNowPlaying() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragAccumX = 0f
                        dragAccumY = 0f
                    },
                    onDragEnd = {
                        when {
                            dragAccumY < -55f && abs(dragAccumY) > abs(dragAccumX) -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenNowPlaying()
                            }
                            dragAccumX < -75f && abs(dragAccumX) > abs(dragAccumY) -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNext()
                            }
                            dragAccumX > 75f && abs(dragAccumX) > abs(dragAccumY) -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPrevious()
                            }
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    dragAccumX += dragAmount.x
                    dragAccumY += dragAmount.y
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = tokens.accentColor,
                    modifier = Modifier.size(20.dp)
                )
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(artworkModel ?: playback.currentArtworkUri)
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
                    text = playback.currentTitle.ifEmpty { stringResource(R.string.now_playing_idle) },
                    color = tokens.textPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (playback.currentArtist.isNotEmpty()) {
                    Text(
                        text = playback.currentArtist,
                        color = tokens.textSecondary,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(tokens.accentColor.copy(alpha = 0.22f))
            ) {
                Icon(
                    imageVector = if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play_pause),
                    tint = tokens.accentColor
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = stringResource(R.string.cd_next),
                    tint = tokens.textPrimary
                )
            }
        }

        LinearProgressIndicator(
            progress = { progress },
            color = tokens.accentColor,
            trackColor = Color.White.copy(alpha = 0.10f),
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
        )
    }
}
