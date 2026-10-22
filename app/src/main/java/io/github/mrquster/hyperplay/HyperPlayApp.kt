package io.github.mrquster.hyperplay

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import io.github.mrquster.hyperplay.audio.AudioEffectsController
import io.github.mrquster.hyperplay.audio.PlayerControllerRepository
import io.github.mrquster.hyperplay.data.LibraryRepository
import io.github.mrquster.hyperplay.data.MediaStoreAudioScanner
import io.github.mrquster.hyperplay.data.PlaybackPreferencesRepository
import io.github.mrquster.hyperplay.data.database.HyperPlayDatabase
import io.github.mrquster.hyperplay.device.DeviceProfile
import io.github.mrquster.hyperplay.device.IslandModePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HyperPlayApp : Application(), ImageLoaderFactory {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _deviceProfileFlow = MutableStateFlow(DeviceProfile())
    val deviceProfileFlow = _deviceProfileFlow.asStateFlow()

    private val _islandPreferenceFlow = MutableStateFlow(IslandModePreference.AUTO)
    val islandPreferenceFlow = _islandPreferenceFlow.asStateFlow()

    fun updateDeviceProfile(profile: DeviceProfile) {
        _deviceProfileFlow.value = profile
    }

    fun updateIslandPreference(pref: IslandModePreference) {
        _islandPreferenceFlow.value = pref
    }

    val database: HyperPlayDatabase by lazy {
        HyperPlayDatabase.getInstance(applicationContext)
    }

    val playbackPrefs: PlaybackPreferencesRepository by lazy {
        PlaybackPreferencesRepository(applicationContext)
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(database.libraryDao(), playbackPrefs)
    }

    val audioScanner: MediaStoreAudioScanner by lazy {
        MediaStoreAudioScanner(
            context = applicationContext,
            scope = applicationScope,
            libraryDao = database.libraryDao(),
            prefsRepo = playbackPrefs
        )
    }

    val playerRepository: PlayerControllerRepository by lazy {
        PlayerControllerRepository(
            context = applicationContext,
            scope = applicationScope,
            libraryRepository = libraryRepository,
            prefsRepository = playbackPrefs
        )
    }

    val audioEffectsController: AudioEffectsController by lazy {
        AudioEffectsController(applicationContext, applicationScope)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("album_art_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
