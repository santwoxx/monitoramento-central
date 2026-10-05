package com.corp.digitaltwin.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.corp.digitaltwin.network.DeviceMode
import com.corp.digitaltwin.state.DeviceTelemetryState
import com.corp.digitaltwin.state.TelemetryStateManager
import kotlinx.coroutines.*

/**
 * Foreground Service com Interface Invasiva por Design.
 * 
 * Transparência e Compliance:
 * A interface deve ser "invasiva por design" para garantir que o usuário do dispositivo corporativo
 * tenha ciência irrefutável de que o dispositivo está sendo telemedido ou transmitido ao vivo.
 * A notificação adota colorização nativa (Verde, Âmbar, Vermelho) e atualiza o HomeScreen Widget.
 */
class SystemStatusOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "digital_twin_system_status"
        const val NOTIFICATION_ID = 9001
        const val ACTION_UPDATE_STATE = "com.corp.digitaltwin.ACTION_UPDATE_STATE"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(DeviceTelemetryState()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun attachTelemetryManager(telemetryManager: TelemetryStateManager) {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            telemetryManager.stateFlow.collect { state ->
                val notification = buildNotification(state)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)

                // Atualiza o Home Screen Widget
                DigitalTwinAppWidget.updateAllWidgets(applicationContext, state)
            }
        }
    }

    private fun buildNotification(state: DeviceTelemetryState): Notification {
        val (color, title, text) = when (state.mode) {
            DeviceMode.IDLE -> Triple(
                Color.rgb(34, 197, 94), // Verde esmeralda
                "Digital Twin: Em Espera",
                "Telemetria básica a cada 60s. Bateria: ${state.batteryPct}%"
            )
            DeviceMode.FOCUS -> Triple(
                Color.rgb(245, 158, 11), // Âmbar vibrante
                "Digital Twin: Modo Foco Ativo",
                "Telemetria contextual (2s) no app: ${state.focusedPackage}"
            )
            DeviceMode.LIVE -> Triple(
                Color.rgb(239, 68, 68), // Vermelho alerta
                "🔴 DIGITAL TWIN: TRANSMISSÃO AO VIVO",
                "A central de operações está visualizando a tela e o áudio em tempo real."
            )
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(color)
            .setColorized(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitoramento Digital Twin",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Status em tempo real da conexão e captura do Digital Twin"
                enableLights(true)
                lightColor = Color.RED
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
