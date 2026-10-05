package com.corp.digitaltwin.media

import android.annotation.SuppressLint
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.corp.digitaltwin.network.BinaryProtocol
import com.corp.digitaltwin.network.ResilientWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/**
 * AudioPlaybackCapturePipeline - Inteligência de Áudio de Vendas DSP.
 * 
 * Funcionalidades Avançadas de DSP:
 * 1. "Sales-Vox" VAD (Voice Activity Detection):
 *    - Filtro de energia passa-faixa (300Hz - 3400Hz).
 *    - Rejeição de ruídos de UI de caixa (Goertzel 2000Hz < 150ms).
 *    - Duração mínima de fala de 250ms e Hangover de 100ms contra clipping de sílabas.
 * 2. Contextual Audio Tagging:
 *    - 0x01: VOICE_PRIMARY (Voz cliente/vendedor em foco).
 *    - 0x02: MEDIA_BACKGROUND (Música de app corporativo/Spotify).
 *    - 0x03: UI_FEEDBACK (Beeps e alertas do sistema).
 *    - 0x04: SILENCE (Pacotes de sincronia A/V).
 * 3. Audio Watermarking:
 *    - Injeção periódica (a cada 10s) de tom espectral de 4kHz por 20ms a 5% de amplitude.
 * 4. A/V Sync Lock:
 *    - Transmissão periódica (a cada 1s) de SYNC_TICK com timestamp de sistema.
 * 5. Loudness Normalization (AGC & Soft Limiter):
 *    - Normalização para alvo de -16 LUFS (broadcast).
 *    - Soft Limiter com saturação tangencial para áudios acima de -6dB.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioPlaybackCapturePipeline(
    private val mediaProjection: MediaProjection,
    private val webSocketClient: ResilientWebSocketClient,
    private val isDndActive: () -> Boolean = { false },
    private val getFocusedPackage: () -> String = { "com.corp.kiosk" },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "SalesAudioDSP"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_COUNT = 1
        
        // Chunks de 20ms: 48000 * 1 canal * 2 bytes * 0.02s = 1920 bytes (960 samples)
        private const val FRAME_DURATION_MS = 20
        private const val SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_DURATION_MS) / 1000 // 960
        private const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2 // 1920 bytes

        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AUDIO_BITRATE = 64_000 // 64 Kbps mono de alta fidelidade
        private const val RING_BUFFER_CAPACITY = 32

        // Parâmetros do Sales-Vox VAD
        private const val MIN_VOICE_STREAK_CHUNKS = 13 // 250ms = 13 chunks de 20ms
        private const val HANGOVER_CHUNKS_COUNT = 5   // 100ms = 5 chunks de 20ms
        private const val VOICE_ENERGY_THRESHOLD = 380.0
        private const val SILENCE_RMS_THRESHOLD = 200.0

        // Watermark: A cada 10s (500 chunks de 20ms)
        private const val WATERMARK_INTERVAL_CHUNKS = 500
    }

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null

    private var recordJob: Job? = null
    private var encoderJob: Job? = null
    private var syncTickJob: Job? = null

    private val pcmRingBuffer = ArrayBlockingQueue<ByteArray>(RING_BUFFER_CAPACITY)

    private val _audioChunks = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val audioChunks: SharedFlow<ByteArray> = _audioChunks.asSharedFlow()

    // Estados do VAD
    private var voiceStreakChunks = 0
    private var hangoverChunks = 0
    private var beepStreakChunks = 0
    private var chunkSequence = 0L

    // Estados do AGC
    private var smoothedGain = 1.0

    // Filtro Passa-Faixa Biquad (300Hz - 3400Hz @ 48kHz)
    private val voiceBandFilter = BiquadBandpassFilter(SAMPLE_RATE.toDouble(), 1010.0, 0.33)

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.compareAndSet(false, true)) {
            Log.i(TAG, "Iniciando Sales Audio Intelligence Pipeline (DSP, Sales-Vox VAD, AGC, Watermark, SyncLock)")
            setupAudioRecordAndEncoder()
            startSyncTickLoop()
        }
    }

    fun stop() {
        if (isRecording.compareAndSet(true, false)) {
            Log.i(TAG, "Encerrando Sales Audio Intelligence Pipeline")
            syncTickJob?.cancel()
            recordJob?.cancel()
            encoderJob?.cancel()
            pcmRingBuffer.clear()

            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao liberar AudioRecord: ${e.message}")
            }

            try {
                audioEncoder?.stop()
                audioEncoder?.release()
                audioEncoder = null
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao liberar AudioEncoder: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioRecordAndEncoder() {
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize, BYTES_PER_FRAME * 4)

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setBufferSizeInBytes(bufferSize)
                .build()

            val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BYTES_PER_FRAME * 2)
            }

            val encoder = MediaCodec.createEncoderByType(AUDIO_MIME)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            record.startRecording()
            audioRecord = record
            audioEncoder = encoder

            startAudioRecordLoop(record)
            startEncoderProcessingLoop(encoder)
            Log.i(TAG, "Hardware AudioRecord e AAC Encoder inicializados.")

        } catch (e: Exception) {
            Log.e(TAG, "Falha na inicialização do pipeline de áudio: ${e.message}", e)
            stop()
        }
    }

    private fun startAudioRecordLoop(record: AudioRecord) {
        recordJob = scope.launch(Dispatchers.IO) {
            val tempBuffer = ByteArray(BYTES_PER_FRAME)

            while (isActive && isRecording.get()) {
                val readBytes = record.read(tempBuffer, 0, tempBuffer.size)
                if (readBytes > 0) {
                    val frameCopy = ByteArray(readBytes)
                    System.arraycopy(tempBuffer, 0, frameCopy, 0, readBytes)

                    if (!pcmRingBuffer.offer(frameCopy)) {
                        pcmRingBuffer.poll()
                        pcmRingBuffer.offer(frameCopy)
                    }
                }
            }
        }
    }

    private fun startEncoderProcessingLoop(encoder: MediaCodec) {
        encoderJob = scope.launch(Dispatchers.IO) {
            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && isRecording.get()) {
                val rawPcmChunk = pcmRingBuffer.poll()
                if (rawPcmChunk != null && rawPcmChunk.size == BYTES_PER_FRAME) {
                    chunkSequence++

                    // 1. ANÁLISE DSP: Energia Total, Voz (300-3400Hz) e Detecção de Beep (2000Hz)
                    val totalRms = calculateRms(rawPcmChunk)
                    val voiceBandRms = calculateVoiceBandRms(rawPcmChunk)
                    val isToneBeep = detect2kHzBeep(rawPcmChunk)

                    // 2. CONTEXTUAL AUDIO TAGGING & SALES-VOX VAD
                    val focusedApp = getFocusedPackage().lowercase()
                    val isMediaBackgroundApp = focusedApp.contains("spotify") ||
                            focusedApp.contains("deezer") ||
                            focusedApp.contains("youtube") ||
                            focusedApp.contains("music") ||
                            focusedApp.contains("podcast") ||
                            focusedApp.contains("radio")

                    val audioContext = determineAudioContext(
                        totalRms = totalRms,
                        voiceRms = voiceBandRms,
                        isToneBeep = isToneBeep,
                        isMediaApp = isMediaBackgroundApp
                    )

                    // 3. SELEÇÃO DE PROCESSAMENTO
                    // Beeps de caixa (UI_NOISE) são descartados para manter áudio limpo
                    if (audioContext == BinaryProtocol.AudioContext.UI_FEEDBACK) {
                        continue
                    }

                    // 4. LOUDNESS NORMALIZATION (AGC + Soft Limiter)
                    var processedPcm = applyAgcAndLimiter(rawPcmChunk, totalRms)

                    // 5. AUDIO WATERMARKING: A cada 10s injeta tom de 4kHz a 5% de amplitude
                    if (chunkSequence % WATERMARK_INTERVAL_CHUNKS == 0L && totalRms > SILENCE_RMS_THRESHOLD) {
                        processedPcm = injectWatermark(processedPcm)
                        Log.d(TAG, "Audio Watermark (4kHz Pulse, 20ms) injetado no stream.")
                    }

                    // 6. ALIMENTA O CODEC AAC COM O PCM TRATADO
                    val inputIndex = encoder.dequeueInputBuffer(5000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(processedPcm)
                            val ptsUs = System.nanoTime() / 1000
                            encoder.queueInputBuffer(inputIndex, 0, processedPcm.size, ptsUs, 0)
                        }
                    }

                    // 7. DRENA PACOTES AAC E TRANSMITE COM TAG CONTEXTUAL
                    var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                    while (outputIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val aacPacket = ByteArray(bufferInfo.size)
                            outputBuffer.get(aacPacket)

                            // Transmite com a Tag Contextual se não estiver em DND
                            if (!isDndActive()) {
                                _audioChunks.tryEmit(aacPacket)
                                webSocketClient.sendAudioPacket(aacPacket, audioContext)
                            }
                        }

                        encoder.releaseOutputBuffer(outputIndex, false)
                        outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                    }
                } else {
                    delay(5)
                }
            }
        }
    }

    /**
     * Lógica de Classificação Contextual e Sales-Vox VAD:
     * - Hangover de 100ms contra corte de palavras.
     * - Duração mínima de 250ms de fala para ativação.
     * - Descarte de picos estreitos de alta frequência (beeps de caixa).
     */
    private fun determineAudioContext(
        totalRms: Double,
        voiceRms: Double,
        isToneBeep: Boolean,
        isMediaApp: Boolean
    ): Byte {
        if (totalRms < SILENCE_RMS_THRESHOLD) {
            voiceStreakChunks = 0
            beepStreakChunks = 0
            if (hangoverChunks > 0) {
                hangoverChunks--
                return BinaryProtocol.AudioContext.VOICE_PRIMARY
            }
            return BinaryProtocol.AudioContext.SILENCE
        }

        if (isToneBeep) {
            beepStreakChunks++
            // Beep curto de caixa (< 150ms = < 8 chunks)
            if (beepStreakChunks < 8) {
                return BinaryProtocol.AudioContext.UI_FEEDBACK
            }
        } else {
            beepStreakChunks = 0
        }

        if (isMediaApp && voiceRms < (totalRms * 0.45)) {
            return BinaryProtocol.AudioContext.MEDIA_BACKGROUND
        }

        // Voz Humana: Energia significativa na faixa 300Hz - 3400Hz
        if (voiceRms >= VOICE_ENERGY_THRESHOLD) {
            voiceStreakChunks++
            if (voiceStreakChunks >= MIN_VOICE_STREAK_CHUNKS) {
                hangoverChunks = HANGOVER_CHUNKS_COUNT
                return BinaryProtocol.AudioContext.VOICE_PRIMARY
            }
        } else {
            voiceStreakChunks = 0
            if (hangoverChunks > 0) {
                hangoverChunks--
                return BinaryProtocol.AudioContext.VOICE_PRIMARY
            }
        }

        return BinaryProtocol.AudioContext.SILENCE
    }

    /**
     * Injeta uma marca d'água acústica inaudível ao cliente mas detectável no espectrograma:
     * Tom de 4kHz com 20ms de duração a 5% do pico de amplitude.
     */
    fun injectWatermark(pcmData: ByteArray): ByteArray {
        val sampleCount = pcmData.size / 2
        val inBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val outBuffer = ByteBuffer.allocate(pcmData.size).order(ByteOrder.LITTLE_ENDIAN)

        val watermarkAmplitude = 0.05 * 32767.0 // ~1638.35
        val angleStep = 2.0 * Math.PI * 4000.0 / SAMPLE_RATE

        for (i in 0 until sampleCount) {
            val originalSample = inBuffer.short.toDouble()
            val watermarkTone = watermarkAmplitude * sin(angleStep * i)
            val combined = (originalSample + watermarkTone).coerceIn(-32768.0, 32767.0)
            outBuffer.putShort(combined.toInt().toShort())
        }
        return outBuffer.array()
    }

    /**
     * Automatic Gain Control (AGC) + Soft Limiter:
     * - Alvo: -16 LUFS (~5200 RMS)
     * - Amplifica sinais fracos (< -30dB) com ganho de até +18dB (8x)
     * - Saturação tangencial suave acima de -6dB (16384) para evitar distorção digital
     */
    private fun applyAgcAndLimiter(pcmData: ByteArray, currentRms: Double): ByteArray {
        val sampleCount = pcmData.size / 2
        val inBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val outBuffer = ByteBuffer.allocate(pcmData.size).order(ByteOrder.LITTLE_ENDIAN)

        val targetRms = 5200.0 // Alvo broadcast ~ -16 LUFS
        val targetGain = if (currentRms > SILENCE_RMS_THRESHOLD) {
            (targetRms / currentRms).coerceIn(0.5, 8.0) // Até 8x (+18dB)
        } else {
            1.0
        }

        // Suavização do ganho para evitar efeito "pumping"
        val alpha = if (targetGain < smoothedGain) 0.20 else 0.05
        smoothedGain = (1.0 - alpha) * smoothedGain + alpha * targetGain

        val threshold = 16384.0 // -6dBFS
        val maxLimit = 32767.0

        for (i in 0 until sampleCount) {
            val sample = inBuffer.short.toDouble() * smoothedGain

            // Soft Limiter (Curva sigmoide tangencial)
            val absVal = abs(sample)
            val limited = if (absVal > threshold) {
                val over = absVal - threshold
                val range = maxLimit - threshold
                val saturated = threshold + range * tanh(over / range)
                sign(sample) * saturated
            } else {
                sample
            }.coerceIn(-32768.0, 32767.0)

            outBuffer.putShort(limited.toInt().toShort())
        }
        return outBuffer.array()
    }

    /**
     * Detector Goertzel de Frequência Única (2000Hz) para identificar beeps de caixa/leitores de código de barras.
     */
    private fun detect2kHzBeep(pcmData: ByteArray): Boolean {
        val sampleCount = pcmData.size / 2
        val inBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)

        val k = (sampleCount * 2000.0 / SAMPLE_RATE).roundToInt()
        val omega = 2.0 * Math.PI * k / sampleCount
        val coeff = 2.0 * cos(omega)

        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var totalPower = 0.0

        for (i in 0 until sampleCount) {
            val sample = inBuffer.short.toDouble()
            totalPower += sample * sample
            s0 = sample + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }

        val powerAt2kHz = s1 * s1 + s2 * s2 - coeff * s1 * s2
        if (totalPower < 1000.0) return false

        // Se mais de 75% da energia do bloco estiver concentrada em 2000Hz, é um beep de leitor/caixa
        val energyRatio = powerAt2kHz / (totalPower * sampleCount / 2.0)
        return energyRatio > 0.70
    }

    private fun calculateVoiceBandRms(pcmData: ByteArray): Double {
        val sampleCount = pcmData.size / 2
        val inBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0

        for (i in 0 until sampleCount) {
            val sample = inBuffer.short.toDouble()
            val filtered = voiceBandFilter.process(sample)
            sumSquares += filtered * filtered
        }
        return sqrt(sumSquares / sampleCount)
    }

    private fun calculateRms(pcm: ByteArray): Double {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return 0.0
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0

        for (i in 0 until sampleCount) {
            val sample = buffer.short.toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / sampleCount)
    }

    /**
     * Dispara marcador SYNC_TICK a cada 1 segundo para sincronização labial (< 50ms).
     */
    private fun startSyncTickLoop() {
        syncTickJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRecording.get()) {
                delay(1000L)
                webSocketClient.sendSyncTick()
            }
        }
    }

    /**
     * Filtro Biquad Direct Form I para isolamento da faixa de voz humana.
     */
    private class BiquadBandpassFilter(fs: Double, f0: Double, q: Double) {
        private val b0: Double
        private val b1: Double = 0.0
        private val b2: Double
        private val a1: Double
        private val a2: Double

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w0 = 2.0 * Math.PI * f0 / fs
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha

            b0 = alpha / a0
            b2 = -alpha / a0
            a1 = (-2.0 * cos(w0)) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }
}