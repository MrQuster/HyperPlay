package io.github.mrquster.hyperplay.ui.theme

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ArtworkPaletteExtractor {

    private val DefaultMint = Color(0xFF00F5D4)

    suspend fun extractAccentColor(
        context: Context,
        artworkModel: Any?
    ): Color = withContext(Dispatchers.IO) {
        if (artworkModel == null) return@withContext DefaultMint

        runCatching {
            val request = ImageRequest.Builder(context)
                .data(artworkModel)
                .size(128)
                .allowHardware(false)
                .build()

            val result = context.imageLoader.execute(request)
            val bitmap = (result as? SuccessResult)?.drawable?.let { it as? BitmapDrawable }?.bitmap
                ?: return@withContext DefaultMint

            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val rawArgb = palette.getVibrantColor(
                palette.getLightVibrantColor(
                    palette.getDominantColor(DefaultMint.toArgb())
                )
            )

            // Гарантируем достаточную светлоту (Lightness >= 0.58), чтобы акцент имел высокий контраст на тёмном стекле
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(rawArgb, hsl)
            if (hsl[2] < 0.58f) hsl[2] = 0.62f
            if (hsl[1] < 0.45f) hsl[1] = 0.70f
            Color(ColorUtils.HSLToColor(hsl))
        }.getOrDefault(DefaultMint)
    }
}
