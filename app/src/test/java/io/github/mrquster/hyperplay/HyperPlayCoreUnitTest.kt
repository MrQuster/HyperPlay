package io.github.mrquster.hyperplay

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import io.github.mrquster.hyperplay.audio.HyperPlayDspAudioProcessor
import io.github.mrquster.hyperplay.data.LyricsParser
import io.github.mrquster.hyperplay.device.IslandStyleOption
import io.github.mrquster.hyperplay.island.HyperPlayIslandJsonBuilder
import io.github.mrquster.hyperplay.island.MusicIslandSnapshot
import io.github.mrquster.hyperplay.util.TextSearchUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class HyperPlayCoreUnitTest {

    @Test
    fun `English and Russian strings_xml have 100 percent identical keys`() {
        val enFile = sequenceOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml")
        ).firstOrNull { it.exists() } ?: return

        val ruFile = File(enFile.parentFile.parentFile, "values-ru/strings.xml")
        assertTrue("Russian strings.xml must exist at ${ruFile.path}", ruFile.exists())

        val enKeys = parseResourceKeys(enFile)
        val ruKeys = parseResourceKeys(ruFile)

        val missingInRu = enKeys - ruKeys
        val missingInEn = ruKeys - enKeys

        assertTrue("Missing keys in values-ru/strings.xml: $missingInRu", missingInRu.isEmpty())
        assertTrue("Missing keys in values/strings.xml: $missingInEn", missingInEn.isEmpty())
    }

    @Test
    fun `C1 Soft-Knee Limiter has zero step discontinuity across 0_75 threshold`() {
        val dsp = HyperPlayDspAudioProcessor()
        val belowKnee = dsp.smoothSoftKneeC1(0.7499)
        val atKnee = dsp.smoothSoftKneeC1(0.7500)
        val aboveKnee = dsp.smoothSoftKneeC1(0.7501)

        // Разница между 0.7499 и 0.7501 должна быть гладкой (~0.0002), а не скачком на 0.13!
        assertEquals(0.7500, atKnee, 1e-6)
        assertTrue("Discontinuity detected below/above knee", abs(aboveKnee - belowKnee) < 0.0005)

        // При экстремальной перегрузке (x = 4.0) выход никогда не превышает 1.0
        val heavyOverload = dsp.smoothSoftKneeC1(4.0)
        assertTrue("Expected < 1.0, got $heavyOverload", heavyOverload in 0.98..0.99999)
    }

    @Test
    fun `TextSearchUtils normalizes diacritics and Cyrillic Yo`() {
        val normalized = TextSearchUtils.normalizeForSearch("Пётр Чайковский — Café")
        assertEquals("петр чаиковскии — cafe", normalized)
    }

    @Test
    fun `LyricsParser parses LRC timestamps and finds active line`() {
        val sampleLrc = """
            [00:10.00] First line of the song
            [00:25.50] Second chorus line
            [01:05.250] Final bridge line
        """.trimIndent()

        val parsed = LyricsParser.parseLrcText(sampleLrc)
        assertNotNull(parsed)
        assertTrue(parsed!!.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals(1, LyricsParser.findActiveLineIndex(parsed.lines, 30_000L))
    }

    @Test
    fun `HyperPlayIslandJsonBuilder builds valid JSON under 4096 bytes`() {
        val snapshot = MusicIslandSnapshot(
            sequenceNumber = 42L,
            isPlaying = true,
            trackTitle = "Midnight City",
            artistName = "M83",
            albumName = "Hurry Up, We're Dreaming",
            positionMs = 60_000L,
            durationMs = 240_000L,
            progressPercent = 25,
            outputDeviceName = "Bluetooth Headphones",
            accentHex = "#00F5D4",
            styleOption = IslandStyleOption.COMPACT,
            allowOnLockscreen = true
        )
        val validated = HyperPlayIslandJsonBuilder.buildAndValidate(snapshot)
        assertTrue(validated.isValid)
        assertTrue(validated.sizeBytes in 100..4096)
    }

    @Test
    fun `HyperPlayDspAudioProcessor amplifies 60Hz bass cleanly with auto-headroom`() {
        val processor = HyperPlayDspAudioProcessor().apply {
            bassStrength = 800
            autoHeadroomEnabled = true
            boostPercent = 120
        }
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)

        val numSamples = 600
        val inBuf = ByteBuffer.allocateDirect(numSamples * 4).order(ByteOrder.nativeOrder())
        for (n in 0 until numSamples) {
            val s = (6000.0 * sin(2.0 * PI * 60.0 * n / 48000.0)).toInt().toShort()
            inBuf.putShort(s)
            inBuf.putShort(s)
        }
        inBuf.flip()

        processor.queueInput(inBuf)
        val outBuf = processor.output
        var maxOut = 0
        while (outBuf.remaining() >= 2) {
            val v = abs(outBuf.short.toInt())
            if (v > maxOut) maxOut = v
        }
        assertTrue("Expected clean amplified signal > 6500, got $maxOut", maxOut in 6500..32767)
    }

    private fun parseResourceKeys(xmlFile: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        val keys = mutableSetOf<String>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            strings.item(i).attributes?.getNamedItem("name")?.nodeValue?.let { keys.add("string:$it") }
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            plurals.item(i).attributes?.getNamedItem("name")?.nodeValue?.let { keys.add("plurals:$it") }
        }
        return keys
    }
}
