package io.github.mrquster.hyperplay.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.audio.PlaybackUiState
import io.github.mrquster.hyperplay.data.LyricsParser
import io.github.mrquster.hyperplay.data.ParsedLyrics
import io.github.mrquster.hyperplay.ui.components.GlassCard
import io.github.mrquster.hyperplay.ui.components.GlassSlider
import io.github.mrquster.hyperplay.ui.components.rememberSystemAnimationsEnabled
import io.github.mrquster.hyperplay.ui.theme.LocalGlassTokens
import io.github.mrquster.hyperplay.util.TimeFormatUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun NowPlayingScreen(
    playback: PlaybackUiState,
    artworkModel: Any?,
    isFavorite: Boolean,
    lyrics: ParsedLyrics?,
    showVisualizer: Boolean,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenEqualizerTab: () -> Unit
) {
    BackHandler(onBack = onClose)

    val tokens = LocalGlassTokens.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var showLyricsMode by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }

    val duration = playback.durationMs.coerceAtLeast(1L)
    val effectivePos = if (isScrubbing) scrubPositionMs else playback.positionMs
    val progressFraction = (effectivePos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    var gestureDragX by remember { mutableFloatStateOf(0f) }
    var gestureDragY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050811))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        gestureDragX = 0f
                        gestureDragY = 0f
                    },
                    onDragEnd = {
                        if (gestureDragY > 110f && abs(gestureDragY) > abs(gestureDragX)) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClose()
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    gestureDragX += dragAmount.x
                    gestureDragY += dragAmount.y
                }
            }
    ) {
        // 1. Размытый фон из обложки текущего трека + затемняющий слой для читаемости (контраст >= 4.5:1)
        if (!tokens.powerSaveMode) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkModel ?: playback.currentArtworkUri)
                    .size(256)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .blur(68.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xB3050812),
                            Color(0xD9050812),
                            Color(0xF204060E)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 2. Верхняя панель: свернуть, выход, переключение Текст / Обложка
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.cd_close_now_playing),
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.now_playing_header),
                        color = tokens.accentColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = playback.activeOutputDeviceName,
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row {
                    IconButton(onClick = { showLyricsMode = !showLyricsMode }) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = stringResource(R.string.cd_toggle_lyrics),
                            tint = if (showLyricsMode) tokens.accentColor else Color.White
                        )
                    }
                    IconButton(onClick = onOpenEqualizerTab) {
                        Icon(
                            imageVector = Icons.Default.Equalizer,
                            contentDescription = stringResource(R.string.nav_eq),
                            tint = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Центральная область: Крупная обложка со свайпами ИЛИ Синхронный текст .lrc
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = showLyricsMode,
                    label = "artworkOrLyrics"
                ) { isLyrics ->
                    if (isLyrics) {
                        SyncedLyricsGlassView(
                            lyrics = lyrics,
                            currentPositionMs = playback.positionMs,
                            accentColor = tokens.accentColor,
                            onSeekToLine = onSeek
                        )
                    } else {
                        var artDragX by remember { mutableFloatStateOf(0f) }
                        val coverShape = RoundedCornerShape(32.dp)

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .fillMaxWidth(0.88f)
                                .shadow(
                                    elevation = 28.dp,
                                    shape = coverShape,
                                    ambientColor = tokens.accentColor.copy(alpha = 0.35f),
                                    spotColor = Color.Black
                                )
                                .clip(coverShape)
                                .background(Color(0xFF0E172A))
                                .border(
                                    width = 1.4.dp,
                                    brush = Brush.linearGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.55f),
                                            tokens.accentColor.copy(alpha = 0.35f),
                                            Color.White.copy(alpha = 0.12f)
                                        )
                                    ),
                                    shape = coverShape
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onTogglePlay()
                                        }
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { artDragX = 0f },
                                        onDragEnd = {
                                            if (artDragX < -85f) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onNext()
                                            } else if (artDragX > 85f) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onPrevious()
                                            }
                                        }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        artDragX += dragAmount.x
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = tokens.accentColor.copy(alpha = 0.45f),
                                modifier = Modifier.size(96.dp)
                            )
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(artworkModel ?: playback.currentArtworkUri)
                                    .size(720)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = stringResource(R.string.cd_album_art),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Стеклянный визуализатор спектра (обрезан по форме капсулы через clip + Offscreen)
            if (showVisualizer) {
                NowPlayingGlassVisualizer(
                    isPlaying = playback.isPlaying,
                    accentColor = tokens.accentColor,
                    secondaryColor = tokens.secondaryAccent
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 5. Стеклянная панель метаданных, перемотки и кнопок управления
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 30.dp,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = playback.currentTitle.ifEmpty { stringResource(R.string.now_playing_idle) },
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${playback.currentArtist.ifEmpty { stringResource(R.string.unknown_artist) }} • ${playback.currentAlbum.ifEmpty { stringResource(R.string.unknown_album) }}",
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.cd_favorite),
                                tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White
                            )
                        }
                    }

                    // Слайдер перемотки с превью времени
                    GlassSlider(
                        value = progressFraction,
                        onValueChange = { frac ->
                            isScrubbing = true
                            scrubPositionMs = (frac * duration).toLong()
                        },
                        onDragFinished = {
                            onSeek(scrubPositionMs)
                            isScrubbing = false
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = TimeFormatUtils.formatDurationMs(effectivePos),
                            color = if (isScrubbing) tokens.accentColor else Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = if (isScrubbing) FontWeight.ExtraBold else FontWeight.Medium
                        )
                        if (playback.sleepTimerRemainingSec > 0 || playback.sleepStopAtEndOfTrack) {
                            Text(
                                text = if (playback.sleepStopAtEndOfTrack) stringResource(R.string.sleep_end_of_track)
                                else "⏱ ${TimeFormatUtils.formatDurationMs(playback.sleepTimerRemainingSec * 1000L)}",
                                color = tokens.accentColor,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = TimeFormatUtils.formatDurationMs(playback.durationMs),
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp
                        )
                    }

                    // Главный ряд кнопок: Shuffle, Prev, Play/Pause, Next, Repeat
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onToggleShuffle) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = stringResource(R.string.cd_shuffle),
                                tint = if (playback.shuffleEnabled) tokens.accentColor else Color(0xFF94A3B8)
                            )
                        }

                        IconButton(
                            onClick = onPrevious,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = stringResource(R.string.cd_previous),
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(66.dp)
                                .shadow(14.dp, CircleShape, ambientColor = tokens.accentColor)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(tokens.accentColor, tokens.secondaryAccent)))
                                .clickable(onClick = onTogglePlay),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.cd_play_pause),
                                tint = Color(0xFF050811),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(
                            onClick = onNext,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = stringResource(R.string.cd_next),
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        IconButton(onClick = onCycleRepeat) {
                            val repIcon = when (playback.repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            }
                            Icon(
                                imageVector = repIcon,
                                contentDescription = stringResource(R.string.cd_repeat),
                                tint = if (playback.repeatMode != Player.REPEAT_MODE_OFF) tokens.accentColor else Color(0xFF94A3B8)
                            )
                        }
                    }

                    // Нижний ряд дополнительных инструментов: Скорость, Таймер сна, Текст .lrc, Очередь
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onCycleSpeed) {
                            Text(
                                text = stringResource(
                                    R.string.speed_fmt,
                                    "%.2f".format(playback.playbackSpeed).trimEnd('0').trimEnd('.')
                                ),
                                color = tokens.accentColor,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }

                        IconButton(onClick = onOpenSleepTimer) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = stringResource(R.string.sleep_timer_btn),
                                tint = if (playback.sleepTimerRemainingSec > 0 || playback.sleepStopAtEndOfTrack) tokens.accentColor else Color(0xFFCBD5E1)
                            )
                        }

                        IconButton(onClick = { showLyricsMode = !showLyricsMode }) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = stringResource(R.string.cd_toggle_lyrics),
                                tint = if (showLyricsMode) tokens.accentColor else Color(0xFFCBD5E1)
                            )
                        }

                        IconButton(onClick = onOpenQueue) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = stringResource(R.string.cd_queue),
                                tint = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricsGlassView(
    lyrics: ParsedLyrics?,
    currentPositionMs: Long,
    accentColor: Color,
    onSeekToLine: (Long) -> Unit
) {
    val listState = rememberLazyListState()

    if (lyrics == null || lyrics.lines.isEmpty()) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            cornerRadius = 28.dp
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    val activeIdx = remember(lyrics, currentPositionMs) {
        if (lyrics.isSynced) LyricsParser.findActiveLineIndex(lyrics.lines, currentPositionMs) else -1
    }

    LaunchedEffect(activeIdx) {
        if (activeIdx >= 0) {
            runCatching {
                listState.animateScrollToItem((activeIdx - 2).coerceAtLeast(0))
            }
        }
    }

    GlassCard(
        modifier = Modifier.fillMaxSize(),
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 24.dp)
        ) {
            itemsIndexed(lyrics.lines, key = { idx, line -> "${idx}_${line.timeMs}" }) { index, line ->
                val isCurrent = index == activeIdx
                Text(
                    text = line.text,
                    color = if (isCurrent) accentColor else Color.White.copy(alpha = if (lyrics.isSynced) 0.52f else 0.88f),
                    fontSize = if (isCurrent) 19.sp else 15.sp,
                    fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Medium,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = lyrics.isSynced) {
                            onSeekToLine(line.timeMs)
                        }
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                )
            }
        }
    }
}

/**
 * Стеклянный визуализатор: обрезается по форме капсулы до рисования (clip + CompositingStrategy.Offscreen)
 * и читает фазу только в фазе отрисовки Canvas (0 рекомпозиций, пауза при !isPlaying).
 */
@Composable
private fun NowPlayingGlassVisualizer(
    isPlaying: Boolean,
    accentColor: Color,
    secondaryColor: Color
) {
    val sysAnim = rememberSystemAnimationsEnabled()
    val animate = isPlaying && sysAnim

    val phaseState: State<Float> = if (animate) {
        val transition = rememberInfiniteTransition(label = "npVis")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "visPhase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val shape = RoundedCornerShape(16.dp)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val phase = phaseState.value
        val barCount = 28
        val spacing = 4.dp.toPx()
        val barW = ((size.width - spacing * (barCount - 1)) / barCount).coerceAtLeast(2.5f)

        for (i in 0 until barCount) {
            val env = sin((i + 1f) / barCount * PI).toFloat()
            val level = if (isPlaying) {
                (0.18f + 0.78f * env * abs(sin(phase + i * 0.42f))).coerceIn(0.12f, 1f)
            } else {
                0.14f
            }
            val barH = size.height * level
            val x = i * (barW + spacing)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(accentColor, secondaryColor)),
                topLeft = Offset(x, (size.height - barH) / 2f),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f)
            )
        }
    }
}
