package io.github.mrquster.hyperplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.github.mrquster.hyperplay.data.LibrarySection
import io.github.mrquster.hyperplay.ui.PlaybackViewModel
import io.github.mrquster.hyperplay.ui.Stage1PlayerScreen
import io.github.mrquster.hyperplay.ui.components.*
import io.github.mrquster.hyperplay.ui.screens.NowPlayingScreen
import io.github.mrquster.hyperplay.ui.screens.OnboardingOverlay
import io.github.mrquster.hyperplay.ui.screens.SettingsScreen
import io.github.mrquster.hyperplay.ui.screens.SoundEqScreen
import io.github.mrquster.hyperplay.ui.screens.SystemScreen
import io.github.mrquster.hyperplay.ui.theme.GlassTokens
import io.github.mrquster.hyperplay.ui.theme.HyperThemeMode
import io.github.mrquster.hyperplay.ui.theme.LocalGlassTokens
import io.github.mrquster.hyperplay.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: PlaybackViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            val scope = rememberCoroutineScope()
            val playback by viewModel.playbackState.collectAsStateWithLifecycle()
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            val selectedTab by viewModel.selectedBottomTab.collectAsStateWithLifecycle()
            val isNowPlayingOpen by viewModel.isNowPlayingExpanded.collectAsStateWithLifecycle()
            val currentTrackItem by viewModel.currentPlayingTrackItem.collectAsStateWithLifecycle()
            val artworkAccent by viewModel.artworkAccentColor.collectAsStateWithLifecycle()
            val currentLyrics by viewModel.currentTrackLyrics.collectAsStateWithLifecycle()

            var hasAudioPerm by remember { mutableStateOf(checkAudioPermission()) }
            val hazeState = remember { HazeState() }

            LaunchedEffect(hasAudioPerm) {
                if (hasAudioPerm) {
                    viewModel.onPermissionGranted()
                }
            }

            val permsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                hasAudioPerm = checkAudioPermission()
                if (hasAudioPerm) {
                    viewModel.onPermissionGranted()
                }
            }

            val sysDark = isSystemInDarkTheme()
            val isDark = when (settings.themeMode) {
                HyperThemeMode.DARK, HyperThemeMode.AMOLED -> true
                HyperThemeMode.LIGHT -> false
                HyperThemeMode.SYSTEM -> sysDark
            }
            val isAmoled = settings.themeMode == HyperThemeMode.AMOLED
            val activeAccent = if (settings.useArtworkAccent) artworkAccent else Color(0xFF00F5D4)

            val tokens = remember(settings, isDark, isAmoled, activeAccent) {
                GlassTokens(
                    isDark = isDark,
                    isAmoled = isAmoled,
                    baseBackground = when {
                        isAmoled -> Color.Black
                        isDark -> Color(0xFF060914)
                        else -> Color(0xFFEEF2F6)
                    },
                    cardBackingColor = when {
                        isAmoled -> Color(0xCC05070E)
                        isDark -> Color(0xB80B1326)
                        else -> Color(0xD9FFFFFF)
                    },
                    accentColor = activeAccent,
                    artworkDominantColor = activeAccent,
                    textPrimary = if (isDark) Color.White else Color(0xFF0F172A),
                    textSecondary = if (isDark) Color(0xE0CBD5E1) else Color(0xB31E293B),
                    glassSurfaceAlpha = settings.glassAlpha,
                    blurRadius = settings.blurIntensityDp.dp,
                    powerSaveMode = settings.powerSaveGlass
                )
            }

            val navItems = listOf(
                GlassTabItem(stringResource(R.string.nav_library), Icons.Default.LibraryMusic),
                GlassTabItem(stringResource(R.string.nav_playlists), Icons.AutoMirrored.Filled.QueueMusic),
                GlassTabItem(stringResource(R.string.nav_eq), Icons.Default.Equalizer),
                GlassTabItem(stringResource(R.string.nav_settings), Icons.Default.Settings),
                GlassTabItem(stringResource(R.string.nav_system), Icons.Default.Memory)
            )

            CompositionLocalProvider(LocalGlassTokens provides tokens) {
                MaterialTheme {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val isWideScreen = maxWidth >= 600.dp

                        if (!settings.onboardingCompleted) {
                            DynamicArtworkBlobBackground(
                                isPlaying = playback.isPlaying,
                                artworkColor = activeAccent
                            )
                            OnboardingOverlay(
                                onFinish = {
                                    scope.launch {
                                        settingsViewModel.prefsRepo.setOnboardingCompleted(true)
                                    }
                                    val perms = mutableListOf<String>()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                                    }
                                    permsLauncher.launch(perms.toTypedArray())
                                }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .haze(state = hazeState)
                            ) {
                                DynamicArtworkBlobBackground(
                                    isPlaying = playback.isPlaying,
                                    artworkColor = activeAccent
                                )

                                Row(modifier = Modifier.fillMaxSize()) {
                                    if (isWideScreen) {
                                        GlassSideNavigationRail(
                                            items = navItems,
                                            selectedIndex = selectedTab,
                                            onSelect = { viewModel.selectBottomTab(it) },
                                            modifier = Modifier
                                                .statusBarsPadding()
                                                .navigationBarsPadding()
                                        )
                                    }

                                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                        when (selectedTab) {
                                            0, 1 -> Stage1PlayerScreen(
                                                viewModel = viewModel,
                                                hasAudioPermission = hasAudioPerm,
                                                onRequestPermission = {
                                                    val perms = mutableListOf<String>()
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                        perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                                                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                                    } else {
                                                        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                                    }
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                                                    }
                                                    permsLauncher.launch(perms.toTypedArray())
                                                },
                                                onOpenAppSettings = {
                                                    startActivity(
                                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                            data = Uri.fromParts("package", packageName, null)
                                                        }
                                                    )
                                                }
                                            )
                                            2 -> SoundEqScreen(
                                                effectsController = viewModel.audioEffects,
                                                playbackViewModel = viewModel
                                            )
                                            3 -> SettingsScreen(
                                                settingsViewModel = settingsViewModel,
                                                playbackViewModel = viewModel,
                                                onOpenEqualizerTab = { viewModel.selectBottomTab(2) },
                                                onOpenSystemTab = { viewModel.selectBottomTab(4) }
                                            )
                                            4 -> SystemScreen(viewModel = settingsViewModel)
                                        }
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (playback.currentTitle.isNotEmpty()) {
                                    MiniPlayerPill(
                                        playback = playback,
                                        artworkModel = currentTrackItem?.resolveBestArtworkModel(),
                                        hazeState = hazeState,
                                        onOpenNowPlaying = { viewModel.setNowPlayingExpanded(true) },
                                        onTogglePlay = { viewModel.togglePlayPause() },
                                        onNext = { viewModel.nextTrack() },
                                        onPrevious = { viewModel.previousTrack() }
                                    )
                                }

                                if (!isWideScreen) {
                                    GlassLensTabBar(
                                        items = navItems,
                                        selectedIndex = selectedTab,
                                        onSelect = { viewModel.selectBottomTab(it) },
                                        hazeState = hazeState
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = isNowPlayingOpen,
                                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                            ) {
                                NowPlayingScreen(
                                    playback = playback,
                                    artworkModel = currentTrackItem?.resolveBestArtworkModel(),
                                    isFavorite = currentTrackItem?.isFavorite == true,
                                    lyrics = currentLyrics,
                                    showVisualizer = settings.showVisualizer,
                                    onClose = { viewModel.setNowPlayingExpanded(false) },
                                    onTogglePlay = { viewModel.togglePlayPause() },
                                    onPrevious = { viewModel.previousTrack() },
                                    onNext = { viewModel.nextTrack() },
                                    onSeek = { viewModel.seekTo(it) },
                                    onToggleShuffle = { viewModel.toggleShuffle() },
                                    onCycleRepeat = { viewModel.cycleRepeat() },
                                    onToggleFavorite = {
                                        currentTrackItem?.let { viewModel.toggleFavorite(it.id) }
                                    },
                                    onCycleSpeed = { viewModel.cycleSpeed() },
                                    onOpenQueue = { viewModel.setNowPlayingExpanded(false) },
                                    onOpenSleepTimer = { viewModel.startSleepTimer(30) },
                                    onOpenEqualizerTab = {
                                        viewModel.setNowPlayingExpanded(false)
                                        viewModel.selectBottomTab(2)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    viewModel.playExternalFile(uri)
                    viewModel.setNowPlayingExpanded(true)
                }
            }
            "io.github.mrquster.hyperplay.SHORTCUT_SHUFFLE" -> {
                val all = viewModel.allTracks.value
                if (all.isNotEmpty()) {
                    viewModel.playFromCurrentList(all.shuffled(), 0)
                }
            }
            "io.github.mrquster.hyperplay.SHORTCUT_CONTINUE" -> {
                viewModel.togglePlayPause()
            }
            "io.github.mrquster.hyperplay.SHORTCUT_FAVORITES" -> {
                viewModel.selectBottomTab(0)
                viewModel.selectSection(LibrarySection.FAVORITES)
            }
        }
    }

    private fun checkAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }
}
