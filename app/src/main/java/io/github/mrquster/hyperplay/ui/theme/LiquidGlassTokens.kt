package io.github.mrquster.hyperplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HyperThemeMode {
    DARK,
    LIGHT,
    AMOLED,
    SYSTEM
}

@Immutable
data class GlassTokens(
    val isDark: Boolean = true,
    val isAmoled: Boolean = false,
    val baseBackground: Color = Color(0xFF060914),
    val cardBackingColor: Color = Color(0xB80B1326),
    val accentColor: Color = Color(0xFF00F5D4),
    val secondaryAccent: Color = Color(0xFF00A8E8),
    val artworkDominantColor: Color = Color(0xFF00F5D4),
    val dangerColor: Color = Color(0xFFFF4D6D),
    val textPrimary: Color = Color.White,
    val textSecondary: Color = Color(0xE0CBD5E1),
    val cardCornerRadius: Dp = 26.dp,
    val pillCornerRadius: Dp = 32.dp,
    val borderStrokeWidth: Dp = 1.2.dp,
    val glassSurfaceAlpha: Float = 0.20f,
    val blurRadius: Dp = 32.dp,
    val parallaxOffset: Offset = Offset.Zero,
    val powerSaveMode: Boolean = false,
    // Сетка отступов 4 / 8 / 12 / 16 / 24dp
    val spacingTiny: Dp = 4.dp,
    val spacingSmall: Dp = 8.dp,
    val spacingMedium: Dp = 12.dp,
    val spacingLarge: Dp = 16.dp,
    val spacingExtraLarge: Dp = 24.dp,
    val minTouchTarget: Dp = 48.dp,
    // Нижний отступ списка, чтобы контент не перекрывался мини-плеером и нижней панелью
    val listBottomPadding: Dp = 172.dp,
    // Типографическая шкала
    val titleLargeSize: TextUnit = 22.sp,
    val sectionHeaderSize: TextUnit = 15.sp,
    val bodyPrimarySize: TextUnit = 14.sp,
    val bodySecondarySize: TextUnit = 12.sp
)

val LocalGlassTokens = staticCompositionLocalOf { GlassTokens() }
