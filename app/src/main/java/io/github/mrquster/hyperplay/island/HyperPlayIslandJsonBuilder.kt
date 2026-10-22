package io.github.mrquster.hyperplay.island

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import io.github.mrquster.hyperplay.R
import io.github.mrquster.hyperplay.device.IslandStyleOption
import io.github.mrquster.hyperplay.util.TimeFormatUtils
import org.json.JSONObject

data class MusicIslandSnapshot(
    val sequenceNumber: Long,
    val isPlaying: Boolean,
    val trackTitle: String,
    val artistName: String,
    val albumName: String,
    val positionMs: Long,
    val durationMs: Long,
    val progressPercent: Int,
    val outputDeviceName: String,
    val accentHex: String,
    val styleOption: IslandStyleOption,
    val allowOnLockscreen: Boolean
)

data class ValidatedMusicIslandJson(
    val jsonString: String,
    val sizeBytes: Int,
    val isValid: Boolean,
    val note: String
)

object HyperPlayIslandJsonBuilder {

    const val EXTRA_MIUI_FOCUS_PARAM = "miui.focus.param"
    const val EXTRA_MIUI_FOCUS_PICS = "miui.focus.pics"
    const val EXTRA_MIUI_FOCUS_ACTIONS = "miui.focus.actions"
    private const val PIC_KEY_PLAY = "miui.focus.pic_hyperplay"
    private const val MAX_JSON_BYTES = 4096

    fun buildAndValidate(snapshot: MusicIslandSnapshot): ValidatedMusicIslandJson {
        return runCatching {
            val safeTitle = snapshot.trackTitle.replace("\n", " ").trim().take(28).ifEmpty { "HyperPlay" }
            val safeArtist = snapshot.artistName.replace("\n", " ").trim().take(26).ifEmpty { "Local Audio" }
            val posFormatted = TimeFormatUtils.formatDurationMs(snapshot.positionMs)
            val durFormatted = TimeFormatUtils.formatDurationMs(snapshot.durationMs)
            val stateBadge = if (snapshot.isPlaying) "$posFormatted / $durFormatted" else "PAUSED • $posFormatted"
            val rightCapsuleText = if (snapshot.styleOption == IslandStyleOption.ICON_ONLY) {
                ""
            } else if (snapshot.isPlaying) {
                "${snapshot.progressPercent}%"
            } else {
                "II"
            }

            val paramIsland = JSONObject().apply {
                put("islandProperty", 1)
                put("islandOrder", true)
                put("enableFloat", false)
                put("reopen", false)
                put("updatable", true)
                put("sequence", snapshot.sequenceNumber)
                put("aodShow", snapshot.allowOnLockscreen)
                put("bgColor", "#070B14")
                put("accentColor", snapshot.accentHex)

                put("bigIslandArea", JSONObject().apply {
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("pic", PIC_KEY_PLAY)
                        put("color", snapshot.accentHex)
                    })
                    if (snapshot.styleOption == IslandStyleOption.COMPACT) {
                        put("imageTextInfoRight", JSONObject().apply {
                            put("type", 2)
                            put("textInfo", JSONObject().apply {
                                put("title", rightCapsuleText)
                                put("color", snapshot.accentHex)
                            })
                        })
                        put("sameWidthDigitInfo", JSONObject().apply {
                            put("digit", rightCapsuleText)
                            put("color", snapshot.accentHex)
                            put("showRingProgress", true)
                            put("progress", snapshot.progressPercent.coerceIn(0, 100))
                        })
                    }
                })

                put("smallIslandArea", JSONObject().apply {
                    put("pic", PIC_KEY_PLAY)
                })
            }

            val paramV2 = JSONObject().apply {
                put("protocol", 1)
                put("business", "music_player_hyperplay")
                put("template", 1)
                put("ticker", "$safeTitle — $safeArtist")
                put("aodTitle", safeTitle)
                put("aodPic", PIC_KEY_PLAY)
                put("enableFloat", false)
                put("updatable", true)
                put("sequence", snapshot.sequenceNumber)

                put("baseInfo", JSONObject().apply {
                    put("type", 2)
                    put("title", safeTitle)
                    put("colorTitle", "#FFFFFF")
                    put("content", "$safeArtist • ${snapshot.outputDeviceName.take(18)}")
                    put("colorContent", "#CBD5E1")
                    put("subContent", stateBadge)
                    put("colorSubContent", snapshot.accentHex)
                    put("bgColor", "#070B14")
                })

                put("progressInfo", JSONObject().apply {
                    put("progress", snapshot.progressPercent.coerceIn(0, 100))
                    put("colorProgress", snapshot.accentHex)
                    put("colorProgressEnd", "#00A8E8")
                    put("colorBg", "#26FFFFFF")
                })

                put("chatInfo", JSONObject().apply {
                    put("picProfile", PIC_KEY_PLAY)
                    put("title", safeTitle)
                    put("colorTitle", "#FFFFFF")
                    put("content", "$safeArtist • $stateBadge")
                    put("colorContent", "#CBD5E1")
                })

                put("param_island", paramIsland)
            }

            val rootJson = JSONObject().apply {
                put("param_v2", paramV2)
            }.toString()

            val bytes = rootJson.toByteArray(Charsets.UTF_8).size
            val valid = bytes in 64..MAX_JSON_BYTES &&
                    rootJson.contains("\"param_v2\"") &&
                    rootJson.contains("\"param_island\"")

            ValidatedMusicIslandJson(
                jsonString = rootJson,
                sizeBytes = bytes,
                isValid = valid,
                note = if (valid) "VALID_OK (${bytes}B)" else "INVALID_SIZE (${bytes}B)"
            )
        }.getOrElse { e ->
            ValidatedMusicIslandJson("{}", 2, false, "ERROR: ${e.localizedMessage}")
        }
    }

    fun attachFocusExtras(
        context: Context,
        extras: Bundle,
        snapshot: MusicIslandSnapshot
    ): ValidatedMusicIslandJson {
        val validated = buildAndValidate(snapshot)
        if (validated.isValid) {
            extras.putString(EXTRA_MIUI_FOCUS_PARAM, validated.jsonString)
        }

        val pics = Bundle().apply {
            putParcelable(PIC_KEY_PLAY, Icon.createWithResource(context, R.drawable.ic_stat_play))
        }
        extras.putBundle(EXTRA_MIUI_FOCUS_PICS, pics)

        val prevPi = createActionPi(context, 401, IslandMediaActionReceiver.ACTION_PREV)
        val playPausePi = createActionPi(context, 402, IslandMediaActionReceiver.ACTION_PLAY_PAUSE)
        val nextPi = createActionPi(context, 403, IslandMediaActionReceiver.ACTION_NEXT)

        val actions = arrayListOf(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_stat_play),
                context.getString(R.string.island_btn_prev),
                prevPi
            ).build(),
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_stat_play),
                if (snapshot.isPlaying) context.getString(R.string.island_btn_pause)
                else context.getString(R.string.island_btn_play),
                playPausePi
            ).build(),
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_stat_play),
                context.getString(R.string.island_btn_next),
                nextPi
            ).build()
        )
        extras.putParcelableArrayList(EXTRA_MIUI_FOCUS_ACTIONS, actions)
        return validated
    }

    private fun createActionPi(context: Context, reqCode: Int, actionStr: String): PendingIntent {
        val intent = Intent().apply {
            component = ComponentName(context, IslandMediaActionReceiver::class.java)
            setPackage(context.packageName)
            action = actionStr
        }
        return PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
