package io.github.mrquster.hyperplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.ui.components.GlassButton
import io.github.mrquster.hyperplay.ui.components.GlassCard
import io.github.mrquster.hyperplay.ui.theme.LocalGlassTokens
import io.github.mrquster.hyperplay.util.AppInfo

private data class OnboardingStepItem(
    val titleRes: Int,
    val descRes: Int,
    val icon: ImageVector
)

@Composable
fun OnboardingOverlay(onFinish: () -> Unit) {
    val tokens = LocalGlassTokens.current
    var step by remember { mutableIntStateOf(0) }

    val steps = listOf(
        OnboardingStepItem(R.string.onb_title_1, R.string.onb_desc_1, Icons.Default.LibraryMusic),
        OnboardingStepItem(R.string.onb_title_2, R.string.onb_desc_2, Icons.Default.GraphicEq),
        OnboardingStepItem(R.string.onb_title_3, R.string.onb_desc_3, Icons.Default.BluetoothAudio),
        OnboardingStepItem(R.string.onb_title_4, R.string.onb_desc_4, Icons.Default.Security)
    )

    val current = steps[step]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.74f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 32.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(tokens.accentColor.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = current.icon,
                        contentDescription = null,
                        tint = tokens.accentColor,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    text = stringResource(current.titleRes),
                    color = tokens.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(current.descRes),
                    color = tokens.textSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                if (step == steps.lastIndex) {
                    Text(
                        text = stringResource(R.string.by_author_fmt, AppInfo.AUTHOR_NAME),
                        color = tokens.textSecondary.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = stringResource(R.string.onb_step_fmt, step + 1, steps.size),
                    color = tokens.accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (step > 0) {
                        GlassButton(
                            text = stringResource(R.string.onb_back),
                            onClick = { step-- }
                        )
                    } else {
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    GlassButton(
                        text = if (step < steps.lastIndex) stringResource(R.string.onb_next)
                        else stringResource(R.string.onb_start),
                        isAccent = true,
                        onClick = {
                            if (step < steps.lastIndex) step++ else onFinish()
                        }
                    )
                }
            }
        }
    }
}
