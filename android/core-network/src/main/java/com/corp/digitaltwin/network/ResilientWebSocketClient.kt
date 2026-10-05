package com.corp.digitaltwin.network

import android.content.Context
import android.util.Log
import com.corp.digitaltwin.database.OutboxDao
import com.corp.digitaltwin.database.OutboxEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Cliente WebSocket Resiliente para Rede Local Wi-Fi (Direct-to-Local).
 * 
 * Funcionalidades:
 * 1. Base URL Dinâmica via SharedPreferences com default "ws://192.168.1.100:5000".
 * 2. Connection Health Check: Ping ativo a cada 30 segundos no estado IDLE.
 * 3. Offline Buffering (Room DB): Se o PC reiniciar ou a rede cair, armazena pacotes de telemetria
 *    e drena em estrita ordem FIFO na reconexão.
 * 4. DeviceTag de 4 Caracteres (ex: "VND1") injetado no payload para roteamento em salas no app.py.
 */
class ResilientWebSocketClient(
    private val context: Context,
    val deviceId: String = "VND1",
    private val outboxDao: OutboxDao,
    private val commandHandler: DefaultCommandHandler,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "ResilientWSClient"
        private const val PREFS_NAME = "digital_twin_config"
        private const val KEY_SERVER_IP = "server_ip"
        private const val DEFAULT_SERVER_URL = "ws://192.168.1.116:5000"

        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 16000L // 16s max em rede local
        private const val BACKOFF_MULTIPLIER = 1.8
        private const val OUTBOX_BATCH_SIZE = 50
        private const val HEALTH_CHECK_INTERVAL_MS = 30000L // 30s Health Check em modos ativos
        private const val SILENT_SYNC_INTERVAL_MS = 300000L // 5 min Silent Sync no modo IDLE
        private const val DEEP_SLEEP_RETRY_INTERVAL_MS = 3600000L // 1 hora Deep Sleep se desconectado em IDLE
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // DeviceTag ASCII de 4 bytes (ex: "VND1")
    val deviceTag: String = deviceId.take(4).padEnd(4, '_')

    private val sequenceCounter = AtomicLong(1L)
    private val isRunning = AtomicBoolean(false)
    private val connectionMutex = Mutex()

    @Volatile
    private var currentServerUrl: String

    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var outboxFlusherJob: Job? = null
    private var healthCheckJob: Job? = null

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    init {
        // Carrega Base URL salva no SharedPreferences ou usa o padrão 192.168.1.100:5000
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentServerUrl = prefs.getString(KEY_SERVER_IP, DEFAULT_SERVER_URL)?.trimEnd('/') ?: DEFAULT_SERVER_URL
    }

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Iniciando ResilientWebSocketClient para device: $deviceTag em $currentServerUrl")
            connect()
            startHealthCheckLoop()
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Encerrando ResilientWebSocketClient")
            healthCheckJob?.cancel()
            reconnectJob?.cancel()
            outboxFlusherJob?.cancel()
            closeCurrentSocket(1000, "Client stopped")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Atualiza a Base URL e persiste no SharedPreferences para reinicializações futuras.
     */
    fun updateServerIp(newIpOrUrl: String) {
        val trimmed = newIpOrUrl.trim()
            .removePrefix("ws://")
            .removePrefix("wss://")
            .removePrefix("http://")
            .removePrefix("https://")
            .split("/")[0] // Pega apenas host:porta se houver caminho
            .trimEnd('/')

        val hostAndPort = if (trimmed.contains(":")) trimmed else "$trimmed:5000"
        val sanitized = "ws://$hostAndPort"

        if (currentServerUrl != sanitized) {
            Log.i(TAG, "Configurando novo IP do PC Local: $sanitized")
            currentServerUrl = sanitized
            
            // Salva no SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_IP, sanitized)
                .apply()

            if (isRunning.get()) {
                scope.launch {
                    connectionMutex.withLock {
                        closeCurrentSocket(1000, "IP alterado pelo usuário")
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                    connect()
                }
            }
        }
    }

    fun getCurrentServerUrl(): String = currentServerUrl

    private fun connect() {
        scope.launch {
            connectionMutex.withLock {
                if (!isRunning.get() || _connectionState.value == ConnectionState.CONNECTED) return@withLock

                _connectionState.value = if (reconnectAttempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING

                val targetEndpoint = "$currentServerUrl/ws/device/$deviceTag"
                val request = Request.Builder()
                    .url(targetEndpoint)
                    .addHeader("X-Device-Tag", deviceTag)
                    .addHeader("X-Protocol-Version", "2.0")
                    .build()

                Log.d(TAG, "Tentando conectar em $targetEndpoint (Tentativa #$reconnectAttempt)")
                webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
            }
        }
    }

    private fun createWebSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "Conexão Wi-Fi com o PC Local estabelecida com sucesso! Código HTTP: ${response.code}")
            scope.launch {
                connectionMutex.withLock {
                    reconnectAttempt = 0
                    isDeepSleep = false
                    _isDeepSleepState.value = false
                    _connectionState.value = ConnectionState.CONNECTED

                    // Envia Hello Frame binário imediato com DeviceTag de 4 bytes
                    val helloPayload = JSONObject().apply {
                        put("deviceTag", deviceTag)
                        put("timestamp", System.currentTimeMillis())
                    }.toString().toByteArray(Charsets.UTF_8)

                    val helloFrame = BinaryProtocol.Frame(
                        messageType = BinaryProtocol.TYPE_HELLO,
                        sequenceNumber = sequenceCounter.getAndIncrement(),
                        deviceTag = deviceTag,
                        payload = helloPayload
                    )
                    sendRawFrame(helloFrame)
                }

                // Inicia descarregamento de pacotes represados no Room DB
                startOutboxFlusher()
            }
        }

        override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
            handleIncomingBinaryMessage(bytes.toByteArray())
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleIncomingTextMessage(text)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket fechado pelo servidor: $code / $reason")
            handleDisconnection()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Falha de rede local com o PC ($currentServerUrl): ${t.message}")
            handleDisconnection()
        }
    }

    /**
     * Envia telemetria com garantia de entrega: se desconectado, persiste no SQLite (Room DB).
     */
    suspend fun sendTelemetry(telemetry: BinaryProtocol.TelemetryPayload) {
        val payloadBytes = BinaryProtocol.encodeTelemetry(telemetry)
        val seq = sequenceCounter.getAndIncrement()
        val timestamp = System.currentTimeMillis()

        if (_connectionState.value == ConnectionState.CONNECTED) {
            val frame = BinaryProtocol.Frame(
                messageType = BinaryProtocol.TYPE_TELEMETRY,
                sequenceNumber = seq,
                timestamp = timestamp,
                deviceTag = deviceTag,
                payload = payloadBytes
            )
            val success = sendRawFrame(frame)
            if (success) return
        }

        // Armazena no Room DB se offline
        val outboxItem = OutboxEntity(
            timestamp = timestamp,
            messageType = BinaryProtocol.TYPE_TELEMETRY,
            sequenceNumber = seq,
            payload = payloadBytes
        )
        outboxDao.enqueue(outboxItem)
    }

    /**
     * Envia fatias de vídeo NAL de 4KB com o tag "VND1" para roteamento na sala correta.
     */
    fun sendVideoNal(nalChunkData: ByteArray, isKeyframe: Boolean): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) return false

        var flags = BinaryProtocol.FLAG_NONE
        if (isKeyframe) flags = (flags.toInt() or BinaryProtocol.FLAG_KEYFRAME.toInt()).toByte()

        val frame = BinaryProtocol.Frame(
            messageType = BinaryProtocol.TYPE_VIDEO_NAL,
            flags = flags,
            sequenceNumber = sequenceCounter.getAndIncrement(),
            deviceTag = deviceTag,
            payload = nalChunkData
        )
        return sendRawFrame(frame)
    }

    /**
     * Envia áudio AAC comprimido (20ms) com Contextual Audio Tagging (1 byte).
     */
    fun sendAudioPacket(
        audioData: ByteArray,
        audioContext: Byte = BinaryProtocol.AudioContext.VOICE_PRIMARY
    ): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        val frame = BinaryProtocol.Frame(
            messageType = BinaryProtocol.TYPE_AUDIO,
            flags = audioContext, // 1 byte AudioContext nas flags do wire protocol
            sequenceNumber = sequenceCounter.getAndIncrement(),
            deviceTag = deviceTag,
            payload = audioData
        )
        return sendRawFrame(frame)
    }

    /**
     * Envia marcador de sincronização A/V (SYNC_TICK) com timestamp do Android a cada 1s.
     */
    fun sendSyncTick(): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        val frame = BinaryProtocol.Frame(
            messageType = BinaryProtocol.TYPE_SYNC_TICK,
            sequenceNumber = sequenceCounter.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            deviceTag = deviceTag,
            payload = ByteArray(0)
        )
        return sendRawFrame(frame)
    }

    private fun sendRawFrame(frame: BinaryProtocol.Frame): Boolean {
        val socket = webSocket ?: return false
        return try {
            val encoded = BinaryProtocol.encodeFrame(frame)
            socket.send(encoded.toByteString())
        } catch (_: Exception) {
            false
        }
    }

    private var currentMode: DeviceMode = DeviceMode.IDLE
    private var isDeepSleep: Boolean = false
    private val _isDeepSleepState = MutableStateFlow(false)
    val isDeepSleepState: StateFlow<Boolean> = _isDeepSleepState.asStateFlow()

    fun updateMode(mode: DeviceMode) {
        val oldMode = currentMode
        currentMode = mode
        if (isDeepSleep && (mode == DeviceMode.FOCUS || mode == DeviceMode.LIVE)) {
            Log.i(TAG, "Saindo do Deep Sleep devido à alteração de modo: $oldMode -> $mode")
            isDeepSleep = false
            _isDeepSleepState.value = false
            reconnectJob?.cancel()
            if (_connectionState.value != ConnectionState.CONNECTED) {
                reconnectAttempt = 0
                connect()
            }
        }
        if (_connectionState.value == ConnectionState.CONNECTED) {
            startHealthCheckLoop()
        }
    }

    /**
     * Silent Sync / Health Check Loop:
     * - Modo IDLE: opera em Silent Sync, disparando Heartbeat de 1 byte a cada 5 minutos.
     * - Modos FOCUS/LIVE: dispara verificação ativa a cada 30 segundos.
     */
    private fun startHealthCheckLoop() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive && isRunning.get()) {
                val interval = if (currentMode == DeviceMode.IDLE) SILENT_SYNC_INTERVAL_MS else HEALTH_CHECK_INTERVAL_MS
                delay(interval)
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    val pingPayload = if (currentMode == DeviceMode.IDLE) byteArrayOf(0x01) else ByteArray(0)
                    val pingFrame = BinaryProtocol.Frame(
                        messageType = BinaryProtocol.TYPE_HEARTBEAT,
                        sequenceNumber = sequenceCounter.getAndIncrement(),
                        deviceTag = deviceTag,
                        payload = pingPayload
                    )
                    val ok = sendRawFrame(pingFrame)
                    if (!ok) {
                        Log.w(TAG, "Heartbeat falhou! PC local pode ter reiniciado.")
                        handleDisconnection()
                    }
                }
            }
        }
    }

    private fun handleDisconnection() {
        scope.launch {
            connectionMutex.withLock {
                _connectionState.value = ConnectionState.DISCONNECTED
                closeCurrentSocket(1001, "Connection lost")
            }

            if (!isRunning.get()) return@launch

            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                val finalDelay = if (currentMode == DeviceMode.IDLE && reconnectAttempt >= 3) {
                    isDeepSleep = true
                    _isDeepSleepState.value = true
                    Log.i(TAG, "🌙 Modo Deep Sleep ativo (IDLE): Tentando reconectar a cada 1 hora para economizar bateria.")
                    DEEP_SLEEP_RETRY_INTERVAL_MS
                } else {
                    val exp = BACKOFF_MULTIPLIER.pow(reconnectAttempt.toDouble()).toLong()
                    val calculatedBackoff = min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * exp)
                    val jitter = Random.nextDouble(0.8, 1.2)
                    (calculatedBackoff * jitter).toLong()
                }

                Log.w(TAG, "Tentando reconectar ao PC ($currentServerUrl) em ${finalDelay}ms (Tentativa #${reconnectAttempt + 1}, DeepSleep: $isDeepSleep)")
                reconnectAttempt++
                delay(finalDelay)
                connect()
            }
        }
    }

    private fun startOutboxFlusher() {
        outboxFlusherJob?.cancel()
        outboxFlusherJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                val batch = outboxDao.getOldestBatch(OUTBOX_BATCH_SIZE)
                if (batch.isEmpty()) break

                val deliveredIds = mutableListOf<Long>()
                for (item in batch) {
                    if (_connectionState.value != ConnectionState.CONNECTED) break

                    val replayFrame = BinaryProtocol.Frame(
                        messageType = item.messageType,
                        flags = BinaryProtocol.FLAG_OUTBOX_REPLAY,
                        sequenceNumber = item.sequenceNumber,
                        timestamp = item.timestamp,
                        deviceTag = deviceTag,
                        payload = item.payload
                    )

                    if (sendRawFrame(replayFrame)) {
                        deliveredIds.add(item.id)
                    } else {
                        outboxDao.incrementRetryCount(item.id)
                        delay(100)
                        break
                    }
                }

                if (deliveredIds.isNotEmpty()) {
                    outboxDao.deleteBatch(deliveredIds)
                    Log.d(TAG, "Drenados ${deliveredIds.size} pacotes acumulados offline.")
                }
            }
        }
    }

    private fun handleIncomingBinaryMessage(bytes: ByteArray) {
        val frame = BinaryProtocol.decodeFrame(bytes) ?: return
        if (frame.messageType == BinaryProtocol.TYPE_COMMAND) {
            parseAndDispatchCommand(frame.payload)
        }
    }

    private fun handleIncomingTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            val action = json.optString("action")
            val commandId = json.optString("commandId", "cmd_${System.currentTimeMillis()}")

            when (action) {
                "SET_MODE" -> {
                    val targetMode = DeviceMode.fromString(json.optString("mode", "IDLE"))
                    scope.launch {
                        commandHandler.dispatchCommand(
                            RemoteCommand.SetMode(commandId, targetMode, System.currentTimeMillis())
                        )
                    }
                }
                "REQUEST_KEYFRAME" -> {
                    scope.launch {
                        commandHandler.dispatchCommand(
                            RemoteCommand.RequestKeyframe(commandId, System.currentTimeMillis())
                        )
                    }
                }
                "CAPTURE_TRIGGER" -> {
                    val reason = json.optString("reason", "manual")
                    scope.launch {
                        commandHandler.dispatchCommand(
                            RemoteCommand.CaptureTrigger(commandId, reason, System.currentTimeMillis())
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao interpretar comando remoto: ${e.message}")
        }
    }

    private fun parseAndDispatchCommand(payload: ByteArray) {
        try {
            handleIncomingTextMessage(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    private fun closeCurrentSocket(code: Int, reason: String?) {
        try { webSocket?.close(code, reason) } catch (_: Exception) {} finally { webSocket = null }
    }
}
