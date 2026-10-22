package io.github.mrquster.hyperplay.device

import androidx.compose.runtime.Immutable

enum class IslandModePreference(val labelEn: String, val labelRu: String) {
    AUTO("Auto (Best for OS)", "Авто (по системе)"),
    SUPER_ISLAND("Xiaomi Super Island", "Xiaomi Super Island"),
    LIVE_UPDATE("Android 16+ Live Update", "Android 16+ Live Update"),
    STANDARD_MEDIA3("Standard Media3 Notification", "Обычное уведомление Media3")
}

enum class IslandStyleOption(val labelEn: String, val labelRu: String) {
    COMPACT("Compact (Icon + Progress)", "Компактный (иконка + прогресс)"),
    ICON_ONLY("Icon Only", "Только иконка"),
    DISABLED("Disabled", "Выкл")
}

enum class SelectedIslandStrategy(val titleEn: String, val titleRu: String) {
    XIAOMI_SUPER_ISLAND("Xiaomi HyperOS Super Island (miui.focus.param)", "Суперостров Xiaomi HyperOS (miui.focus.param)"),
    ANDROID_16_LIVE_UPDATE("Android 16+ Promoted Live Update", "Живое уведомление Android 16+ (Live Update)"),
    STANDARD_MEDIA3("Standard Android Media3 Notification", "Стандартная медиа-карточка Media3")
}

@Immutable
data class SystemExecutionPlan(
    val selectedStrategy: SelectedIslandStrategy,
    val selectionReasonEn: String,
    val selectionReasonRu: String,
    val useFullAgslGlass: Boolean,
    val useSideRailNavigation: Boolean
)

object FeatureRegistry {

    fun resolvePlan(
        profile: DeviceProfile,
        preference: IslandModePreference = IslandModePreference.AUTO,
        powerSaveMode: Boolean = false
    ): SystemExecutionPlan {
        val isXiaomiHyperOrMiui = profile.osFamily.name.contains("HYPEROS") ||
                profile.osFamily.name.contains("MIUI")

        val (strategy, reasonEn, reasonRu) = when (preference) {
            IslandModePreference.SUPER_ISLAND -> Triple(
                SelectedIslandStrategy.XIAOMI_SUPER_ISLAND,
                "Forced by user in Settings (Xiaomi Super Island)",
                "Принудительно выбран пользователем в настройках (Xiaomi Super Island)"
            )
            IslandModePreference.LIVE_UPDATE -> Triple(
                SelectedIslandStrategy.ANDROID_16_LIVE_UPDATE,
                "Forced by user in Settings (Android 16+ Live Update)",
                "Принудительно выбран пользователем в настройках (Android 16+ Live Update)"
            )
            IslandModePreference.STANDARD_MEDIA3 -> Triple(
                SelectedIslandStrategy.STANDARD_MEDIA3,
                "Forced by user in Settings (Standard Media3 Notification)",
                "Принудительно выбран пользователем в настройках (Обычное уведомление Media3)"
            )
            IslandModePreference.AUTO -> {
                if (isXiaomiHyperOrMiui && profile.focusNotificationStatus != TriStateStatus.UNSUPPORTED) {
                    Triple(
                        SelectedIslandStrategy.XIAOMI_SUPER_ISLAND,
                        "Auto-selected: Xiaomi HyperOS detected (${profile.osVersion})",
                        "Автовыбор: обнаружена оболочка Xiaomi HyperOS (${profile.osVersion})"
                    )
                } else if (profile.apiLevel >= 36 && profile.capabilities.contains(Capability.ANDROID_16_LIVE_UPDATES)) {
                    Triple(
                        SelectedIslandStrategy.ANDROID_16_LIVE_UPDATE,
                        "Auto-selected: Android 16+ (API ${profile.apiLevel}) promoted notifications supported",
                        "Автовыбор: поддержка Live Updates на Android 16+ (API ${profile.apiLevel})"
                    )
                } else {
                    Triple(
                        SelectedIslandStrategy.STANDARD_MEDIA3,
                        "Auto-selected: universal Media3 MediaStyle notification for ${profile.osFamily.displayNameEn}",
                        "Автовыбор: универсальное медиауведомление Media3 для ${profile.osFamily.displayNameRu}"
                    )
                }
            }
        }

        val fullGlass = !powerSaveMode &&
                profile.apiLevel >= 33 &&
                profile.capabilities.contains(Capability.AGSL_RUNTIME_SHADER) &&
                profile.hardwareSpecs.totalRamMb >= 3500L

        val sideRail = profile.formFactor.name.contains("TABLET") ||
                profile.formFactor.name.contains("FOLDABLE") ||
                profile.smallestScreenWidthDp >= 600

        return SystemExecutionPlan(
            selectedStrategy = strategy,
            selectionReasonEn = reasonEn,
            selectionReasonRu = reasonRu,
            useFullAgslGlass = fullGlass,
            useSideRailNavigation = sideRail
        )
    }
}
