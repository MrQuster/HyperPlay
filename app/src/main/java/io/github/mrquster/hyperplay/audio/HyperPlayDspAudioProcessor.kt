package io.github.mrquster.hyperplay.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class DspOneSecondSnapshot(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encodingName: String,
    val inPeak: Double,
    val inRms: Double,
    val outPeak: Double,
    val outRms: Double,
    val headroomDb: Float,
    val limiterGrDb: Float,
    val totalClippedSamples: Long
)

@OptIn(UnstableApi::class)
class HyperPlayDspAudioProcessor : AudioProcessor {

    @Volatile var preampDb: Float = 0f
    @Volatile var maxPositiveEqBandDb: Float = 0f
    @Volatile var boostPercent: Int = 100
    @Volatile var bassStrength: Int = 0         // 0..1000 -> 0..+12 dB Low-Shelf (95 Hz)
    @Volatile var virtualizerStrength: Int = 0  // 0..1000 -> 1.0..2.15x Mid/Side width
    @Volatile var balanceLeftRight: Float = 0f  // -1.0 (Left) .. +1.0 (Right)
    @Volatile var monoEnabled: Boolean = false
    @Volatile var softLimiterEnabled: Boolean = true

    // Флаги из панели «Диагностика звука» (A/B)
    @Volatile var pureBypassAll: Boolean = false
    @Volatile var softwareDspEnabled: Boolean = true
    @Volatile var autoHeadroomEnabled: Boolean = true
    @Volatile var dither16BitEnabled: Boolean = true

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // Коэффициенты Biquad Low-Shelf фильтра (95 Гц)
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var lastConfiguredBass = -1
    private var lastSampleRate = -1

    // Линии задержки фильтра
    private var lx1 = 0.0; private var lx2 = 0.0; private var ly1 = 0.0; private var ly2 = 0.0
    private var rx1 = 0.0; private var rx2 = 0.0; private var ry1 = 0.0; private var ry2 = 0.0

    // По-сэмпловые сглаживатели (против щелчков при кручении ползунков и старте/seek)
    private var smoothedGainLinear: Double = 1.0
    private var postFlushRampGain: Double = 0.0
    private var limiterEnvelope: Double = 1.0

    // Быстрый генератор псевдослучайных чисел (Xorshift) без аллокаций для TPDF Dither
    private var rngState: Long = 0x5DEECE66DL

    // Метрики реального времени для «Диагностики звука» (без аллокаций в аудио-потоке)
    @Volatile private var windowInPeak: Double = 0.0
    @Volatile private var windowInSumSq: Double = 0.0
    @Volatile private var windowOutPeak: Double = 0.0
    @Volatile private var windowOutSumSq: Double = 0.0
    @Volatile private var windowFramesCount: Long = 0L
    @Volatile private var currentAutoHeadroomDb: Float = 0f
    @Volatile private var maxLimiterReductionLinear: Double = 1.0
    private val clippedSamplesCounter = AtomicLong(0L)

    fun applyAbSwitches(sw: AudioAbSwitches) {
        pureBypassAll = sw.pureBypassAll
        softwareDspEnabled = sw.softwareDspEnabled
        autoHeadroomEnabled = sw.autoHeadroomEnabled
        dither16BitEnabled = sw.dither16BitEnabled
    }

    fun resetDiagnosticCounters() {
        clippedSamplesCounter.set(0L)
        windowInPeak = 0.0
        windowOutPeak = 0.0
        windowInSumSq = 0.0
        windowOutSumSq = 0.0
        windowFramesCount = 0L
        maxLimiterReductionLinear = 1.0
    }

    fun pollAndResetOneSecondMetrics(): DspOneSecondSnapshot {
        val frames = windowFramesCount.coerceAtLeast(1L)
        val inPeak = windowInPeak
        val outPeak = windowOutPeak
        val inRms = sqrt(windowInSumSq / frames)
        val outRms = sqrt(windowOutSumSq / frames)
        val minLimGain = maxLimiterReductionLinear.coerceIn(0.1, 1.0)
        val grDb = (20.0 * log10(minLimGain)).toFloat()

        windowInPeak = 0.0
        windowOutPeak = 0.0
        windowInSumSq = 0.0
        windowOutSumSq = 0.0
        windowFramesCount = 0L
        maxLimiterReductionLinear = 1.0

        val encName = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> "PCM_FLOAT (32-bit)"
            C.ENCODING_PCM_16BIT -> "PCM_16BIT"
            else -> "PCM"
        }

        return DspOneSecondSnapshot(
            sampleRateHz = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 48000,
            channelCount = inputAudioFormat.channelCount.takeIf { it > 0 } ?: 2,
            encodingName = encName,
            inPeak = inPeak,
            inRms = inRms,
            outPeak = outPeak,
            outRms = outRms,
            headroomDb = currentAutoHeadroomDb,
            limiterGrDb = grDb,
            totalClippedSamples = clippedSamplesCounter.get()
        )
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 2 && inputAudioFormat.channelCount != 1) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        recalculateBiquadIfNeeded(inputAudioFormat.sampleRate, bassStrength)
        return outputAudioFormat
    }

    override fun isActive(): Boolean {
        return inputAudioFormat != AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        val sr = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        val effBass = if (pureBypassAll || !softwareDspEnabled) 0 else bassStrength
        recalculateBiquadIfNeeded(sr, effBass)

        // Вычисляем целевое усиление и автоматическую компенсацию Headroom
        val bassBoostDb = (effBass / 1000.0) * 12.0
        val eqPosDb = if (pureBypassAll) 0.0 else maxPositiveEqBandDb.coerceAtLeast(0f).toDouble()
        val autoHeadroomDb = if (!pureBypassAll && autoHeadroomEnabled) {
            // Компенсируем 70% суммы подъёма баса и эквалайзера, чтобы не перегружать каскад до лимитера
            -((bassBoostDb * 0.65) + (eqPosDb * 0.55)).coerceIn(0.0, 14.0)
        } else 0.0
        currentAutoHeadroomDb = autoHeadroomDb.toFloat()

        val targetGainLinear = if (pureBypassAll || !softwareDspEnabled) {
            1.0
        } else {
            val userBoostLinear = (boostPercent.coerceIn(50, 400) / 100.0)
            val totalDb = preampDb.toDouble() + autoHeadroomDb
            userBoostLinear * 10.0.pow(totalDb / 20.0)
        }

        val bal = if (pureBypassAll || !softwareDspEnabled) 0.0 else balanceLeftRight.toDouble().coerceIn(-1.0, 1.0)
        val leftBal = if (bal > 0.0) 1.0 - bal else 1.0
        val rightBal = if (bal < 0.0) 1.0 + bal else 1.0
        val useVirt = !pureBypassAll && softwareDspEnabled && virtualizerStrength > 0
        val stereoWidth = 1.0 + (virtualizerStrength.coerceIn(0, 1000) / 1000.0) * 1.10
        val useBass = effBass > 0
        val isMono = !pureBypassAll && softwareDspEnabled && monoEnabled
        val useLimiter = !pureBypassAll && softwareDspEnabled && softLimiterEnabled
        val useDither = !pureBypassAll && dither16BitEnabled

        // Коэффициент плавного 15 мс микро-рампа после flush()/seek
        val rampStep = 1.0 / (sr * 0.004).coerceAtLeast(32.0)
        val gainSmoothCoeff = 0.0015

        val channels = inputAudioFormat.channelCount
        var localInPeak = windowInPeak
        var localInSumSq = windowInSumSq
        var localOutPeak = windowOutPeak
        var localOutSumSq = windowOutSumSq
        var localFrames = 0L

        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= channels * 2) {
                smoothedGainLinear += (targetGainLinear - smoothedGainLinear) * gainSmoothCoeff
                if (postFlushRampGain < 1.0) {
                    postFlushRampGain = (postFlushRampGain + rampStep).coerceAtMost(1.0)
                }
                val effectiveGain = smoothedGainLinear * postFlushRampGain

                if (channels == 2) {
                    val rawL = inputBuffer.short / 32768.0
                    val rawR = inputBuffer.short / 32768.0

                    val inPairPeak = max(abs(rawL), abs(rawR))
                    if (inPairPeak > localInPeak) localInPeak = inPairPeak
                    localInSumSq += (rawL * rawL + rawR * rawR) * 0.5

                    var left = rawL * effectiveGain * leftBal
                    var right = rawR * effectiveGain * rightBal

                    if (useBass) {
                        val outL = b0 * left + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2
                        lx2 = lx1; lx1 = left; ly2 = ly1; ly1 = if (outL.isFinite()) outL else 0.0
                        left = ly1

                        val outR = b0 * right + b1 * rx1 + b2 * rx2 - a1 * ry1 - a2 * ry2
                        rx2 = rx1; rx1 = right; ry2 = ry1; ry1 = if (outR.isFinite()) outR else 0.0
                        right = ry1
                    }

                    if (useVirt) {
                        val mid = (left + right) * 0.5
                        val side = (left - right) * 0.5 * stereoWidth
                        left = mid + side
                        right = mid - side
                    }

                    if (isMono) {
                        val m = (left + right) * 0.5
                        left = m
                        right = m
                    }

                    if (useLimiter) {
                        val pairAbs = max(abs(left), abs(right))
                        val envGain = computeLookaheadEnvelopeGain(pairAbs)
                        left = smoothSoftKneeC1(left * envGain)
                        right = smoothSoftKneeC1(right * envGain)
                    } else {
                        if (abs(left) >= 0.999 || abs(right) >= 0.999) {
                            clippedSamplesCounter.incrementAndGet()
                        }
                        left = left.coerceIn(-1.0, 1.0)
                        right = right.coerceIn(-1.0, 1.0)
                    }

                    val outPairPeak = max(abs(left), abs(right))
                    if (outPairPeak > localOutPeak) localOutPeak = outPairPeak
                    localOutSumSq += (left * left + right * right) * 0.5
                    localFrames++

                    val ditherL = if (useDither) nextTpdfDither() else 0.0
                    val ditherR = if (useDither) nextTpdfDither() else 0.0

                    val sL = ((left * 32767.0) + ditherL).roundToInt().coerceIn(-32768, 32767).toShort()
                    val sR = ((right * 32767.0) + ditherR).roundToInt().coerceIn(-32768, 32767).toShort()
                    buffer.putShort(sL)
                    buffer.putShort(sR)
                } else {
                    val rawM = inputBuffer.short / 32768.0
                    if (abs(rawM) > localInPeak) localInPeak = abs(rawM)
                    localInSumSq += rawM * rawM

                    var mono = rawM * effectiveGain
                    if (useLimiter) {
                        val envGain = computeLookaheadEnvelopeGain(abs(mono))
                        mono = smoothSoftKneeC1(mono * envGain)
                    } else {
                        if (abs(mono) >= 0.999) clippedSamplesCounter.incrementAndGet()
                        mono = mono.coerceIn(-1.0, 1.0)
                    }

                    if (abs(mono) > localOutPeak) localOutPeak = abs(mono)
                    localOutSumSq += mono * mono
                    localFrames++

                    val dither = if (useDither) nextTpdfDither() else 0.0
                    buffer.putShort(((mono * 32767.0) + dither).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= channels * 4) {
                smoothedGainLinear += (targetGainLinear - smoothedGainLinear) * gainSmoothCoeff
                if (postFlushRampGain < 1.0) {
                    postFlushRampGain = (postFlushRampGain + rampStep).coerceAtMost(1.0)
                }
                val effectiveGain = smoothedGainLinear * postFlushRampGain

                if (channels == 2) {
                    val rawL = inputBuffer.float.toDouble()
                    val rawR = inputBuffer.float.toDouble()

                    val inPairPeak = max(abs(rawL), abs(rawR))
                    if (inPairPeak > localInPeak) localInPeak = inPairPeak
                    localInSumSq += (rawL * rawL + rawR * rawR) * 0.5

                    var left = rawL * effectiveGain * leftBal
                    var right = rawR * effectiveGain * rightBal

                    if (useBass) {
                        val outL = b0 * left + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2
                        lx2 = lx1; lx1 = left; ly2 = ly1; ly1 = if (outL.isFinite()) outL else 0.0
                        left = ly1

                        val outR = b0 * right + b1 * rx1 + b2 * rx2 - a1 * ry1 - a2 * ry2
                        rx2 = rx1; rx1 = right; ry2 = ry1; ry1 = if (outR.isFinite()) outR else 0.0
                        right = ry1
                    }

                    if (useVirt) {
                        val mid = (left + right) * 0.5
                        val side = (left - right) * 0.5 * stereoWidth
                        left = mid + side
                        right = mid - side
                    }

                    if (isMono) {
                        val m = (left + right) * 0.5
                        left = m
                        right = m
                    }

                    if (useLimiter) {
                        val pairAbs = max(abs(left), abs(right))
                        val envGain = computeLookaheadEnvelopeGain(pairAbs)
                        left = smoothSoftKneeC1(left * envGain)
                        right = smoothSoftKneeC1(right * envGain)
                    } else {
                        if (abs(left) >= 0.999 || abs(right) >= 0.999) {
                            clippedSamplesCounter.incrementAndGet()
                        }
                        left = left.coerceIn(-1.0, 1.0)
                        right = right.coerceIn(-1.0, 1.0)
                    }

                    val outPairPeak = max(abs(left), abs(right))
                    if (outPairPeak > localOutPeak) localOutPeak = outPairPeak
                    localOutSumSq += (left * left + right * right) * 0.5
                    localFrames++

                    buffer.putFloat(left.toFloat())
                    buffer.putFloat(right.toFloat())
                } else {
                    val rawM = inputBuffer.float.toDouble()
                    var mono = rawM * effectiveGain
                    if (useLimiter) {
                        val envGain = computeLookaheadEnvelopeGain(abs(mono))
                        mono = smoothSoftKneeC1(mono * envGain)
                    } else {
                        mono = mono.coerceIn(-1.0, 1.0)
                    }
                    localFrames++
                    buffer.putFloat(mono.toFloat())
                }
            }
        }

        if (inputBuffer.hasRemaining()) {
            buffer.put(inputBuffer)
        }

        windowInPeak = localInPeak
        windowInSumSq = localInSumSq
        windowOutPeak = localOutPeak
        windowOutSumSq = localOutSumSq
        windowFramesCount += localFrames

        buffer.flip()
        outputBuffer = buffer
    }

    /**
     * Обрабатывает пару стереосэмплов для встроенного генератора эталонного сигнала (Синус 1 кГц / Розовый шум)
     */
    fun processNormalizedStereoSampleForTest(
        rawLeft: Double,
        rawRight: Double,
        sampleRate: Int
    ): Pair<Double, Double> {
        val effBass = if (pureBypassAll || !softwareDspEnabled) 0 else bassStrength
        recalculateBiquadIfNeeded(sampleRate, effBass)

        val bassBoostDb = (effBass / 1000.0) * 12.0
        val autoHeadroomDb = if (!pureBypassAll && autoHeadroomEnabled) {
            -(bassBoostDb * 0.65).coerceIn(0.0, 12.0)
        } else 0.0
        val gain = if (pureBypassAll || !softwareDspEnabled) 1.0
        else (boostPercent / 100.0) * 10.0.pow((preampDb + autoHeadroomDb) / 20.0)

        var left = rawLeft * gain
        var right = rawRight * gain

        if (effBass > 0) {
            val outL = b0 * left + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2
            lx2 = lx1; lx1 = left; ly2 = ly1; ly1 = if (outL.isFinite()) outL else 0.0
            left = ly1

            val outR = b0 * right + b1 * rx1 + b2 * rx2 - a1 * ry1 - a2 * ry2
            rx2 = rx1; rx1 = right; ry2 = ry1; ry1 = if (outR.isFinite()) outR else 0.0
            right = ry1
        }

        if (!pureBypassAll && softwareDspEnabled && softLimiterEnabled) {
            val env = computeLookaheadEnvelopeGain(max(abs(left), abs(right)))
            left = smoothSoftKneeC1(left * env)
            right = smoothSoftKneeC1(right * env)
        } else {
            left = left.coerceIn(-1.0, 1.0)
            right = right.coerceIn(-1.0, 1.0)
        }
        return left to right
    }

    /**
     * C1-непрерывный мягкий лимитер без ступеньки производной:
     * При |x| <= 0.75 возвращает x без искажений (1:1).
     * При |x| > 0.75 плавно сжимает сигнал по формуле: 0.75 + 0.25 * tanh((|x| - 0.75) / 0.25).
     * В точке x = 0.75 и значение функции (0.75), и её первая производная (1.0) совпадают!
     */
    fun smoothSoftKneeC1(sample: Double): Double {
        if (!sample.isFinite()) return 0.0
        val absX = abs(sample)
        val knee = 0.75
        if (absX <= knee) return sample
        if (absX >= 1.25) {
            clippedSamplesCounter.incrementAndGet()
        }
        val headroom = 1.0 - knee // 0.25
        val compressed = knee + headroom * tanh((absX - knee) / headroom)
        return compressed.withSign(sample).coerceIn(-0.9999, 0.9999)
    }

    /**
     * Огибающая автоматического контроля пиков (быстрая атака ~0.8 мс, плавное восстановление ~60 мс)
     */
    private fun computeLookaheadEnvelopeGain(peakAbs: Double): Double {
        val ceiling = 0.92
        val desiredGain = if (peakAbs > ceiling) (ceiling / peakAbs) else 1.0
        val coeff = if (desiredGain < limiterEnvelope) 0.04 else 0.0004 // быстрая атака, плавный релиз
        limiterEnvelope += (desiredGain - limiterEnvelope) * coeff
        val clampedEnv = limiterEnvelope.coerceIn(0.15, 1.0)
        if (clampedEnv < maxLimiterReductionLinear) {
            maxLimiterReductionLinear = clampedEnv
        }
        return clampedEnv
    }

    /**
     * TPDF (Triangular Probability Density Function) дизеринг в диапазоне [-1.0, +1.0] младшего бита (LSB)
     * без создания объектов Random в куче.
     */
    private fun nextTpdfDither(): Double {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        rngState = x
        val r1 = ((x and 0xFFFFL).toDouble() / 65535.0) - 0.5
        val r2 = (((x ushr 16) and 0xFFFFL).toDouble() / 65535.0) - 0.5
        return r1 + r2
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        // Сбрасываем огибающую микро-рампа в 0.0, чтобы первые 15 мс после seek/смены трека шли с плавным fade-in без щелчка!
        postFlushRampGain = 0.25
        lx1 = 0.0; lx2 = 0.0; ly1 = 0.0; ly2 = 0.0
        rx1 = 0.0; rx2 = 0.0; ry1 = 0.0; ry2 = 0.0
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        lastConfiguredBass = -1
        lastSampleRate = -1
    }

    private fun recalculateBiquadIfNeeded(sampleRate: Int, strength0to1000: Int) {
        val sr = sampleRate.coerceAtLeast(8000)
        val clamped = strength0to1000.coerceIn(0, 1000)
        if (sr == lastSampleRate && clamped == lastConfiguredBass) return
        lastSampleRate = sr
        lastConfiguredBass = clamped

        if (clamped == 0) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
            return
        }

        val gainDb = (clamped / 1000.0) * 12.0
        val f0 = 95.0
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / sr
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        b0 = (a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
        b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
        b2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
        a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
        a2 = ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0
    }
}
