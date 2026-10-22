package io.github.mrquster.hyperplay.ui.components

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mrquster.hyperplay.ui.theme.LocalGlassTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0.01f
        }.getOrDefault(true)
    }
}

@Composable
fun DynamicArtworkBlobBackground(
    isPlaying: Boolean,
    artworkColor: Color
) {
    val tokens = LocalGlassTokens.current
    val sysAnim = rememberSystemAnimationsEnabled()
    val shouldAnimate = isPlaying && sysAnim && !tokens.powerSaveMode

    val phaseState: State<Float> = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "bgBlobs")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 14000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "blobPhase"
        )
    } else {
        remember { mutableFloatStateOf(0.75f) }
    }

    val animatedBlob1 by animateColorAsState(
        targetValue = artworkColor,
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow),
        label = "blob1Color"
    )
    val animatedBlob2 by animateColorAsState(
        targetValue = tokens.secondaryAccent,
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow),
        label = "blob2Color"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.baseBackground)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .blur(if (tokens.powerSaveMode) 28.dp else 64.dp)
        ) {
            val phase = phaseState.value
            val w = size.width
            val h = size.height
            val alpha = when {
                tokens.isAmoled -> 0.16f
                tokens.isDark -> 0.22f
                else -> 0.18f
            }

            drawCircle(
                color = animatedBlob1.copy(alpha = alpha),
                radius = w * 0.45f,
                center = Offset(
                    x = w * (0.26f + 0.10f * cos(phase)),
                    y = h * (0.22f + 0.08f * sin(phase))
                )
            )
            drawCircle(
                color = animatedBlob2.copy(alpha = alpha * 0.85f),
                radius = w * 0.48f,
                center = Offset(
                    x = w * (0.75f + 0.10f * sin(phase * 0.8f)),
                    y = h * (0.68f + 0.08f * cos(phase * 0.8f))
                )
            )
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = LocalGlassTokens.current.cardCornerRadius,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(cornerRadius)
    val alpha = tokens.glassSurfaceAlpha

    val surfaceColors = if (tokens.isDark) {
        listOf(
            Color.White.copy(alpha = (alpha + 0.05f).coerceAtMost(0.36f)),
            Color.White.copy(alpha = (alpha * 0.40f).coerceAtLeast(0.05f)),
            tokens.accentColor.copy(alpha = (alpha * 0.35f).coerceAtMost(0.18f))
        )
    } else {
        listOf(
            Color.White.copy(alpha = 0.90f),
            Color.White.copy(alpha = 0.76f)
        )
    }

    val borderColors = listOf(
        Color.White.copy(alpha = if (tokens.isDark) 0.50f else 0.90f),
        tokens.accentColor.copy(alpha = 0.28f),
        Color.White.copy(alpha = 0.12f)
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = if (tokens.powerSaveMode) 4.dp else 14.dp,
                shape = shape,
                ambientColor = tokens.accentColor.copy(alpha = 0.16f),
                spotColor = Color.Black.copy(alpha = 0.45f)
            )
            .clip(shape)
            .background(tokens.cardBackingColor)
            .background(Brush.linearGradient(surfaceColors))
            .border(tokens.borderStrokeWidth, Brush.linearGradient(borderColors), shape)
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun GlassSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalGlassTokens.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(tokens.spacingSmall)
    ) {
        Text(
            text = title,
            color = tokens.accentColor,
            fontSize = tokens.sectionHeaderSize,
            fontWeight = FontWeight.ExtraBold
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(tokens.spacingMedium),
                content = content
            )
        }
    }
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isAccent: Boolean = false,
    isDanger: Boolean = false
) {
    val tokens = LocalGlassTokens.current
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btnScale"
    )
    val shape = RoundedCornerShape(22.dp)

    val bgBrush = when {
        isDanger -> Brush.linearGradient(listOf(tokens.dangerColor.copy(alpha = 0.38f), tokens.dangerColor.copy(alpha = 0.18f)))
        isAccent -> Brush.linearGradient(listOf(tokens.accentColor.copy(alpha = 0.38f), tokens.secondaryAccent.copy(alpha = 0.22f)))
        else -> Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (tokens.isDark) 0.20f else 0.85f),
                Color.White.copy(alpha = if (tokens.isDark) 0.10f else 0.65f)
            )
        )
    }

    val strokeColor = when {
        isDanger -> tokens.dangerColor.copy(alpha = 0.80f)
        isAccent -> tokens.accentColor.copy(alpha = 0.80f)
        else -> Color.White.copy(alpha = 0.40f)
    }

    Row(
        modifier = modifier
            .heightIn(min = tokens.minTouchTarget)
            .scale(scale)
            .clip(shape)
            .background(bgBrush)
            .border(1.1.dp, strokeColor, shape)
            .clickable(interactionSource = interactionSource, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = if (isDanger) Color(0xFFFF8FA3) else if (isAccent) tokens.accentColor else tokens.textPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = tokens.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalGlassTokens.current
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(
                if (selected) tokens.accentColor.copy(alpha = 0.32f)
                else Color.White.copy(alpha = if (tokens.isDark) 0.10f else 0.65f)
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) tokens.accentColor else Color.White.copy(alpha = 0.28f),
                shape = shape
            )
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) tokens.accentColor else tokens.textPrimary,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = LocalGlassTokens.current
    val haptics = LocalHapticFeedback.current
    val thumbProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "switchThumb"
    )

    Box(
        modifier = Modifier
            .width(54.dp)
            .height(30.dp)
            .clip(CircleShape)
            .background(
                if (checked) tokens.accentColor.copy(alpha = 0.38f)
                else Color.White.copy(alpha = if (tokens.isDark) 0.16f else 0.55f)
            )
            .border(1.2.dp, if (checked) tokens.accentColor else Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = 22.dp * thumbProgress)
                .size(22.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color.White, if (checked) tokens.accentColor else Color.LightGray)))
        )
    }
}

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    modifier: Modifier = Modifier,
    onDragFinished: (() -> Unit)? = null
) {
    val tokens = LocalGlassTokens.current
    val haptics = LocalHapticFeedback.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)
    var isDragging by remember { mutableStateOf(false) }

    val dropletScale by animateFloatAsState(
        targetValue = if (isDragging) 1.24f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "sliderDrop"
    )

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    val newFrac = (offset.x / size.width).coerceIn(0f, 1f)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnValueChange(valueRange.start + newFrac * span)
                    currentOnDragFinished?.invoke()
                }
            }
            .pointerInput(valueRange) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        currentOnDragFinished?.invoke()
                    },
                    onDragCancel = {
                        isDragging = false
                        currentOnDragFinished?.invoke()
                    }
                ) { change, _ ->
                    change.consume()
                    val newFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                    currentOnValueChange(valueRange.start + newFrac * span)
                }
            }
    ) {
        val trackH = 10.dp.toPx()
        val cy = size.height / 2f
        val padX = 12.dp.toPx()
        val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
        val thumbX = padX + usableW * fraction

        drawRoundRect(
            color = Color.White.copy(alpha = if (tokens.isDark) 0.16f else 0.55f),
            topLeft = Offset(padX, cy - trackH / 2f),
            size = Size(usableW, trackH),
            cornerRadius = CornerRadius(trackH / 2f)
        )

        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(tokens.secondaryAccent, tokens.accentColor)),
            topLeft = Offset(padX, cy - trackH / 2f),
            size = Size((thumbX - padX).coerceAtLeast(trackH), trackH),
            cornerRadius = CornerRadius(trackH / 2f)
        )

        val r = 11.dp.toPx() * dropletScale
        drawCircle(
            color = tokens.accentColor.copy(alpha = 0.32f),
            radius = r * 1.35f,
            center = Offset(thumbX, cy)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, tokens.accentColor),
                center = Offset(thumbX - r * 0.3f, cy - r * 0.3f),
                radius = r * 1.2f
            ),
            radius = r,
            center = Offset(thumbX, cy)
        )
        drawCircle(
            color = Color.White,
            radius = r,
            center = Offset(thumbX, cy),
            style = Stroke(width = 1.4.dp.toPx())
        )
    }
}

data class GlassTabItem(
    val title: String,
    val icon: ImageVector
)
