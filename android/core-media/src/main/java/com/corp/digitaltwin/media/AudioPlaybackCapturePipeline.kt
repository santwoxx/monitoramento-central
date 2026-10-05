package com.corp.digitaltwin.media

import android.annotation.SuppressLint
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.corp.digitaltwin.network.ResilientWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipeline de Captura de Áudio de Saída (Android 10+ / API 29+).
 * 
 * Implementação de Baixa Latência e Otimizações Vercel/Edge:
 * 1. Captura exclusiva de USAGE_MEDIA (evita captação ambiental do microfone e eco acústico).
 * 2. PCM Ring Buffer via ArrayBlockingQueue: Desacopla o hardware AudioRecord do MediaCodec encoder.
 * 3. Alimentação do MediaCodec AAC via dequeueInputBuffer / queueInputBuffer (sem uso inválido de Surface).
 * 4. Silence Suppression (VAD por Energia RMS): Se o áudio estiver em silêncio por >= 500ms,
 *    os pacotes são suprimidos para poupar largura de banda no túnel e processamento no dashboard Vercel.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioPlaybackCapturePipeline(
    private val mediaProjection: MediaProjection,
    private val webSocketClient: ResilientWebSocketClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "AudioCapturePipeline"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_COUNT = 1
        
        // Chunks de 20ms: 48000 * 1 canal * 2 bytes * 0.02s = 1920 bytes
        private const val FRAME_DURATION_MS = 20
        private const val BYTES_PER_FRAME = (SAMPLE_RATE * CHANNEL_COUNT * 2 * FRAME_DURATION_MS) / 1000 // 1920 bytes

        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AUDIO_BITRATE = 64_000 // 64 Kbps mono de alta fidelidade
        private const val RING_BUFFER_CAPACITY = 32 // ~640ms de buffer contra starvation

        // Silence Suppression: Limiar de energia RMS
        private const val SILENCE_RMS_THRESHOLD = 250.0 // Abaixo disso é ruído de fundo/silêncio digital
        private const val SILENCE_TIMEOUT_MS = 500L // 500ms de silêncio contínuo aciona o corte
    }

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null

    private var recordJob: Job? = null
    private var encoderJob: Job? = null

    // PCM Ring Buffer desacoplado
    private val pcmRingBuffer = ArrayBlockingQueue<ByteArray>(RING_BUFFER_CAPACITY)

    // SharedFlow para observabilidade reativa
    private val _audioChunks = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val audioChunks: SharedFlow<ByteArray> = _audioChunks.asSharedFlow()

    // Controle de Supressão de Silêncio
    private var consecutiveSilenceMs = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.compareAndSet(false, true)) {
            Log.i(TAG, "Iniciando AudioPlaybackCapturePipeline (USAGE_MEDIA, 20ms Chunks, VAD Ativo)")
            setupAudioRecordAndEncoder()
        }
    }

    fun stop() {
        if (isRecording.compareAndSet(true, false)) {
            Log.i(TAG, "Encerrando AudioPlaybackCapturePipeline")
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
            // 1. Configuração da Captura de Áudio Interno (Android 10+)
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

            // 2. Configuração do MediaCodec de Áudio (AAC Low-Complexity)
            val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BYTES_PER_FRAME * 2)
            }

            val encoder = MediaCodec.createEncoderByType(AUDIO_MIME)
            // Codecs de áudio recebem buffers em memória (null surface)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            record.startRecording()
            audioRecord = record
            audioEncoder = encoder

            startAudioRecordLoop(record)
            startEncoderProcessingLoop(encoder)
            Log.i(TAG, "AudioRecord e MediaCodec de Áudio configurados com sucesso.")

        } catch (e: Exception) {
            Log.e(TAG, "Falha na inicialização do pipeline de áudio: ${e.message}", e)
            stop()
        }
    }

    /**
     * Loop de Leitura do AudioRecord rodando em Dispatchers.IO.
     * Alimenta o PCM Ring Buffer com frames regulares de 20ms.
     */
    private fun startAudioRecordLoop(record: AudioRecord) {
        recordJob = scope.launch(Dispatchers.IO) {
            val tempBuffer = ByteArray(BYTES_PER_FRAME)

            while (isActive && isRecording.get()) {
                val readBytes = record.read(tempBuffer, 0, tempBuffer.size)
                if (readBytes > 0) {
                    val frameCopy = ByteArray(readBytes)
                    System.arraycopy(tempBuffer, 0, frameCopy, 0, readBytes)

                    // Se a fila estiver cheia (starvation/congestionamento do codec), descarta o mais antigo
                    if (!pcmRingBuffer.offer(frameCopy)) {
                        pcmRingBuffer.poll()
                        pcmRingBuffer.offer(frameCopy)
                    }
                }
            }
        }
    }

    /**
     * Loop de Codificação AAC com VAD (Silence Suppression).
     * Drena o Ring Buffer, alimenta o codec via dequeueInputBuffer e transmite se houver energia acústica.
     */
    private fun startEncoderProcessingLoop(encoder: MediaCodec) {
        encoderJob = scope.launch(Dispatchers.IO) {
            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && isRecording.get()) {
                val pcmChunk = pcmRingBuffer.poll()
                if (pcmChunk != null) {
                    // Cálculo de Energia RMS do chunk PCM para detecção de silêncio
                    val rmsEnergy = calculateRms(pcmChunk)
                    val isSilent = rmsEnergy < SILENCE_RMS_THRESHOLD

                    if (isSilent) {
                        consecutiveSilenceMs += FRAME_DURATION_MS
                    } else {
                        consecutiveSilenceMs = 0L // Reinicia ao detectar voz ou som
                    }

                    val shouldSuppress = consecutiveSilenceMs >= SILENCE_TIMEOUT_MS

                    // 1. Alimenta o encoder AAC com o buffer PCM
                    val inputIndex = encoder.dequeueInputBuffer(5000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(pcmChunk)
                            val ptsUs = System.nanoTime() / 1000
                            encoder.queueInputBuffer(inputIndex, 0, pcmChunk.size, ptsUs, 0)
                        }
                    }

                    // 2. Drena pacotes AAC comprimidos
                    var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                    while (outputIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val aacPacket = ByteArray(bufferInfo.size)
                            outputBuffer.get(aacPacket)

                            // Só transmite via WebSocket se NÃO for silêncio prolongado
                            if (!shouldSuppress) {
                                _audioChunks.tryEmit(aacPacket)
                                webSocketClient.sendAudioPacket(aacPacket)
                            }
                        }

                        encoder.releaseOutputBuffer(outputIndex, false)
                        outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                    }
                } else {
                    // Espera suave de 5ms caso a fila esteja momentaneamente vazia
                    delay(5)
                }
            }
        }
    }

    /**
     * Calcula o Root Mean Square (RMS) dos samples de áudio PCM 16-bit.
     */
    private fun calculateRms(pcm: ByteArray): Double {
        var sumSquares = 0.0
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return 0.0

        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 2) {
            val sample = buffer.short.toDouble()
            sumSquares += sample * sample
        }
        return kotlin.math.sqrt(sumSquares / sampleCount)
    }
}