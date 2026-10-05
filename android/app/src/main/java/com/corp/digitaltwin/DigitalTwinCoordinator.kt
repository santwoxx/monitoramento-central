package com.corp.digitaltwin

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import com.corp.digitaltwin.database.OutboxDatabase
import com.corp.digitaltwin.media.AudioPlaybackCapturePipeline
import com.corp.digitaltwin.media.MediaProjectionPipeline
import com.corp.digitaltwin.network.DefaultCommandHandler
import com.corp.digitaltwin.network.DeviceMode
import com.corp.digitaltwin.network.RemoteCommand
import com.corp.digitaltwin.network.ResilientWebSocketClient
import com.corp.digitaltwin.state.TelemetryStateManager
import kotlinx.coroutines.*

/**
 * Orquestrador Central do Digital Twin no Dispositivo Android.
 * 
 * Responsabilidades:
 * 1. Interface Unificada de Controle: start(), stop(), updateMode(mode) e requestSyncFrame().
 * 2. Suporte a I-Frame On-Demand: Força a emissão imediata de KeyFrames IDR para que operadores
 *    no dashboard Vercel decodifiquem a tela em < 100ms ao abrir o monitor.
 * 3. Injeção de Dependências e Integração:
 *    - Transporte: ResilientWebSocketClient (com Base URL dinâmica para túneis / ADB)
 *    - Vídeo: MediaProjectionPipeline (720p @ 1.5Mbps CBR, Zero-Copy H.264)
 *    - Áudio: AudioPlaybackCapturePipeline (Captura USAGE_MEDIA com VAD 500ms)
 *    - Telemetria: TelemetryStateManager (Amostragem adaptativa 60s/2s/1s)
 */
class DigitalTwinCoordinator(
    private val context: Context,
    val deviceId: String,
    initialServerUrl: String = "ws://10.0.2.2:5000"
) {
    companion object {
        private const val TAG = "DigitalTwinCoordinator"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val database = OutboxDatabase.getInstance(context)
    val commandHandler = DefaultCommandHandler()

    val webSocketClient = ResilientWebSocketClient(
        context = context,
        deviceId = deviceId,
        outboxDao = database.outboxDao(),
        commandHandler = commandHandler
    )

    init {
        if (initialServerUrl.isNotBlank()) {
            webSocketClient.updateServerIp(initialServerUrl)
        }
    }

    val telemetryManager = TelemetryStateManager(
        context = context,
        deviceId = deviceId,
        webSocketClient = webSocketClient
    )

    private var mediaPipeline: MediaProjectionPipeline? = null
    private var audioPipeline: AudioPlaybackCapturePipeline? = null
    private var currentMode: DeviceMode = DeviceMode.IDLE

    /**
     * Inicia os serviços de telemetria em segundo plano e a conexão de transporte.
     */
    fun start() {
        Log.i(TAG, "Iniciando Digital Twin Coordinator para o dispositivo: $deviceId")
        webSocketClient.start()
        telemetryManager.start()
        listenToRemoteCommands()
    }

    /**
     * Anexa a sessão de MediaProjection obtida através da Activity do sistema.
     * Suporta passagem direta do objeto MediaProjection.
     */
    fun attachMediaProjection(mediaProjection: MediaProjection) {
        Log.i(TAG, "MediaProjection anexado. Inicializando pipelines de vídeo e áudio em hardware.")

        // 1. Pipeline de Vídeo (720p @ 1.5 Mbps, Zero-Copy + Watermark)
        mediaPipeline?.stop()
        mediaPipeline = MediaProjectionPipeline(
            context = context,
            mediaProjection = mediaProjection,
            webSocketClient = webSocketClient,
            deviceTag = deviceId.take(4).padEnd(4, '_'),
            width = 1280,
            height = 720
        ).also {
            it.start()
            it.updateMode(currentMode)
        }

        // 2. Pipeline de Áudio (Captura USAGE_MEDIA com Silence Suppression)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioPipeline?.stop()
            audioPipeline = AudioPlaybackCapturePipeline(
                mediaProjection = mediaProjection,
                webSocketClient = webSocketClient
            ).also { it.start() }
        }
    }

    /**
     * Sobrecarga de conveniência para inicialização direta a partir do onActivityResult de uma Activity.
     */
    fun attachMediaProjection(resultCode: Int, resultData: Intent, mpm: MediaProjectionManager) {
        val mp = mpm.getMediaProjection(resultCode, resultData)
        if (mp != null) {
            attachMediaProjection(mp)
        } else {
            Log.e(TAG, "Falha ao obter instância de MediaProjection a partir do Intent.")
        }
    }

    /**
     * Atualiza o modo de operação do dispositivo em runtime:
     * - IDLE: 5 FPS, 300 Kbps, telemetria a cada 60s
     * - FOCUS: 15 FPS, 800 Kbps, telemetria a cada 2s
     * - LIVE: 30 FPS, 1.5 Mbps, stream contínuo
     */
    fun updateMode(mode: DeviceMode) {
        if (currentMode != mode) {
            Log.i(TAG, "Alterando modo operacional: $currentMode -> $mode")
            currentMode = mode
            telemetryManager.setMode(mode)
            mediaPipeline?.updateMode(mode)

            // Se transitando para LIVE, força I-Frame imediato
            if (mode == DeviceMode.LIVE) {
                requestSyncFrame()
            }
        }
    }

    /**
     * Força a emissão imediata de um I-Frame (IDR Sync Frame) no encoder de vídeo.
     * Permite que operadores recém-conectados no Vercel iniciem a decodificação em < 100ms.
     */
    fun requestSyncFrame() {
        Log.d(TAG, "Forçando emissão de I-Frame IDR sob demanda.")
        mediaPipeline?.requestSyncFrame()
    }

    /**
     * Permite atualizar a URL ou IP do Gateway local (ex: 192.168.1.100 ou ws://...) em runtime.
     */
    fun updateServerIp(newIpOrUrl: String) {
        webSocketClient.updateServerIp(newIpOrUrl)
    }

    fun updateServerUrl(newUrl: String) {
        webSocketClient.updateServerIp(newUrl)
    }

    private fun listenToRemoteCommands() {
        scope.launch {
            commandHandler.commandEvents.collect { command ->
                when (command) {
                    is RemoteCommand.SetMode -> {
                        Log.i(TAG, "Comando remoto executado: SET_MODE -> ${command.targetMode}")
                        updateMode(command.targetMode)
                    }
                    is RemoteCommand.RequestKeyframe -> {
                        Log.i(TAG, "Comando remoto executado: REQUEST_KEYFRAME")
                        requestSyncFrame()
                    }
                    is RemoteCommand.CaptureTrigger -> {
                        Log.i(TAG, "Comando remoto executado: CAPTURE_TRIGGER (${command.reason})")
                        requestSyncFrame()
                    }
                    is RemoteCommand.Unknown -> {
                        Log.w(TAG, "Comando remoto não identificado: ${command.rawCommand}")
                    }
                }
            }
        }
    }

    /**
     * Encerra de forma limpa todos os pipelines de hardware e sockets.
     */
    fun stop() {
        Log.i(TAG, "Encerrando Digital Twin Coordinator")
        scope.cancel()
        audioPipeline?.stop()
        mediaPipeline?.stop()
        telemetryManager.stop()
        webSocketClient.stop()
    }
}
