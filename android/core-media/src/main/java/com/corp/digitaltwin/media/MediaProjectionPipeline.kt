package com.corp.digitaltwin.media

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.corp.digitaltwin.network.DeviceMode
import com.corp.digitaltwin.network.ResilientWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipeline de Mídia de Baixa Latência (<200ms) para Rede Wi-Fi Local.
 * 
 * Especificações Finais:
 * - Resolução: 1280x720 (720p HD)
 * - Taxa de Quadros: 30 FPS
 * - Bitrate: 1.5 Mbps CBR
 * - Perfil: H.264 Baseline Level 4.0 (Zero B-Frames)
 * - Zero-Copy: VirtualDisplay conectado diretamente ao MediaCodec
 * - Watermark Overlay: Estampa visual de DeviceID e Timestamp no canto inferior direito
 * - I-Frame On-Demand: forceKeyFrame() para handshake instantâneo com o LiveMonitor no React
 */
class MediaProjectionPipeline(
    private val context: Context,
    private var mediaProjection: MediaProjection?,
    private val webSocketClient: ResilientWebSocketClient,
    private val deviceTag: String = "VND1",
    private val width: Int = 1280,
    private val height: Int = 720,
    private val densityDpi: Int = 320,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "MediaProjectionPipeline"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_USEC = 10000L
        const val MAX_CHUNK_PAYLOAD_SIZE = 4096

        /**
         * Factory para criação a partir do resultado de permissão da Activity
         */
        fun createFromIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            webSocketClient: ResilientWebSocketClient,
            deviceTag: String = "VND1"
        ): MediaProjectionPipeline {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, resultData)
            return MediaProjectionPipeline(context, mp, webSocketClient, deviceTag)
        }
    }

    private val isStreaming = AtomicBoolean(false)
    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encodingJob: Job? = null
    private var watermarkJob: Job? = null
    private var spsPpsBuffer: ByteArray? = null

    // Watermark Overlay View (renderizado sobre a tela para gravação em hardware no VirtualDisplay)
    private var watermarkTextView: TextView? = null
    private var windowManager: WindowManager? = null

    private val _videoChunks = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val videoChunks: SharedFlow<ByteArray> = _videoChunks.asSharedFlow()

    private var currentMode = DeviceMode.IDLE
    private var currentBitrate = 1_500_000
    private var currentFps = 30

    fun start() {
        if (isStreaming.compareAndSet(false, true)) {
            Log.i(TAG, "Iniciando MediaProjectionPipeline [720p @ 30fps, 1.5Mbps CBR, Tag: $deviceTag]")
            setupWatermarkOverlay()
            setupAndStartEncoder()
        }
    }

    fun stop() {
        if (isStreaming.compareAndSet(true, false)) {
            Log.i(TAG, "Encerrando MediaProjectionPipeline")
            encodingJob?.cancel()
            watermarkJob?.cancel()
            removeWatermarkOverlay()

            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { mediaCodec?.stop(); mediaCodec?.release() } catch (_: Exception) {}
            virtualDisplay = null
            mediaCodec = null
        }
    }

    fun updateMode(mode: DeviceMode) {
        currentMode = mode
        when (mode) {
            DeviceMode.IDLE -> { currentFps = 5; currentBitrate = 300_000 }
            DeviceMode.FOCUS -> { currentFps = 15; currentBitrate = 800_000 }
            DeviceMode.LIVE -> { currentFps = 30; currentBitrate = 1_500_000 }
        }

        mediaCodec?.let { codec ->
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, currentBitrate)
                }
                codec.setParameters(params)
                Log.i(TAG, "Bitrate atualizado dinamicamente para $currentBitrate bps (Modo $mode)")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao atualizar parâmetros dinâmicos: ${e.message}")
            }
        }
    }

    /**
     * Força a emissão imediata de um I-Frame IDR (Instantaneous Decoder Refresh).
     */
    fun forceKeyFrame() {
        mediaCodec?.let { codec ->
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                codec.setParameters(params)
                Log.d(TAG, "⚡ I-Frame IDR forçado com sucesso para o deviceTag: $deviceTag")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao forçar I-Frame: ${e.message}")
            }
        }
    }

    fun requestSyncFrame() {
        forceKeyFrame()
    }

    /**
     * Estampa de Água Visual (Watermark Overlay):
     * Adiciona um carimbo visual no canto inferior direito que é capturado automaticamente
     * pelo VirtualDisplay na Input Surface de hardware sem degradação de CPU.
     */
    private fun setupWatermarkOverlay() {
        scope.launch(Dispatchers.Main) {
            try {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@launch
                
                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    x = 24
                    y = 24
                }

                val textView = TextView(context).apply {
                    setBackgroundColor(Color.argb(180, 15, 23, 42)) // Fundo escuro semitransparente
                    setTextColor(Color.rgb(56, 189, 248)) // Ciano vibrante
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setPadding(14, 8, 14, 8)
                    text = "ID: $deviceTag | LIVE"
                }

                windowManager?.addView(textView, params)
                watermarkTextView = textView

                // Loop para atualizar o timestamp do watermark a cada 1 segundo
                watermarkJob = scope.launch(Dispatchers.Main) {
                    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    while (isActive && isStreaming.get()) {
                        val currentTime = dateFormat.format(Date())
                        watermarkTextView?.text = "🛡️ $deviceTag | $currentTime | WI-FI"
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Permissão SYSTEM_ALERT_WINDOW não concedida ou overlay indisponível: ${e.message}")
            }
        }
    }

    private fun removeWatermarkOverlay() {
        scope.launch(Dispatchers.Main) {
            try {
                watermarkTextView?.let { windowManager?.removeView(it) }
                watermarkTextView = null
            } catch (_: Exception) {}
        }
    }

    private fun setupAndStartEncoder() {
        val mp = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection é nulo. Chame attachMediaProjection antes de start().")
            return
        }

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, currentFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_OPERATING_RATE, currentFps)
                setInteger(MediaFormat.KEY_PRIORITY, 0)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, currentFps.toFloat())
                }
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec

            virtualDisplay = mp.createVirtualDisplay(
                "DigitalTwinMirror",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            startEncoderDrainLoop(codec)
            Log.i(TAG, "MediaCodec 720p Zero-Copy e VirtualDisplay inicializados com sucesso.")

        } catch (e: Exception) {
            Log.e(TAG, "Erro crítico na inicialização do encoder: ${e.message}", e)
            stop()
        }
    }

    private fun startEncoderDrainLoop(codec: MediaCodec) {
        encodingJob = scope.launch(Dispatchers.IO) {
            val bufferInfo = MediaCodec.BufferInfo()
            var frameSeq = 0L

            while (isActive && isStreaming.get()) {
                val outputBufferIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                } catch (e: Exception) {
                    break
                }

                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        extractConfigParameters(codec.outputFormat)
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                            val rawData = ByteArray(bufferInfo.size)
                            outputBuffer.get(rawData)

                            if (isCodecConfig) {
                                spsPpsBuffer = rawData
                            } else {
                                frameSeq++
                                val packetToSend = if (isKeyframe && spsPpsBuffer != null) {
                                    concatenateBuffers(spsPpsBuffer!!, rawData)
                                } else {
                                    rawData
                                }
                                packetizeAndSendNal(packetToSend, isKeyframe, frameSeq)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }
    }

    private fun packetizeAndSendNal(nalData: ByteArray, isKeyframe: Boolean, frameSeq: Long) {
        val totalSize = nalData.size
        val totalChunks = (totalSize + MAX_CHUNK_PAYLOAD_SIZE - 1) / MAX_CHUNK_PAYLOAD_SIZE

        var offset = 0
        for (chunkIdx in 0 until totalChunks) {
            val chunkSize = minOf(MAX_CHUNK_PAYLOAD_SIZE, totalSize - offset)
            val packetBuffer = ByteBuffer.allocate(8 + chunkSize)
            packetBuffer.putShort(chunkIdx.toShort())
            packetBuffer.putShort(totalChunks.toShort())
            packetBuffer.putInt((frameSeq and 0xFFFFFFFFL).toInt())
            packetBuffer.put(nalData, offset, chunkSize)

            val chunkBytes = packetBuffer.array()
            _videoChunks.tryEmit(chunkBytes)
            webSocketClient.sendVideoNal(chunkBytes, isKeyframe && chunkIdx == 0)
            offset += chunkSize
        }
    }

    private fun extractConfigParameters(format: MediaFormat) {
        val sps = format.getByteBuffer("csd-0")
        val pps = format.getByteBuffer("csd-1")
        if (sps != null && pps != null) {
            val spsBytes = ByteArray(sps.remaining()); sps.get(spsBytes)
            val ppsBytes = ByteArray(pps.remaining()); pps.get(ppsBytes)
            spsPpsBuffer = concatenateBuffers(spsBytes, ppsBytes)
        }
    }

    private fun concatenateBuffers(b1: ByteArray, b2: ByteArray): ByteArray {
        val result = ByteArray(b1.size + b2.size)
        System.arraycopy(b1, 0, result, 0, b1.size)
        System.arraycopy(b2, 0, result, b1.size, b2.size)
        return result
    }
}