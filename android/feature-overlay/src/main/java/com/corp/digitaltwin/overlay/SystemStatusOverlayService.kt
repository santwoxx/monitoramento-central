package com.corp.digitaltwin.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.corp.digitaltwin.network.DeviceMode
import com.corp.digitaltwin.state.AuditLogTracker
import com.corp.digitaltwin.state.DeviceTelemetryState
import com.corp.digitaltwin.state.TelemetryStateManager
import kotlinx.coroutines.*

/**
 * SystemStatusOverlayService - Foreground Service de Baixo Perfil (Stealth Notification).
 * 
 * Arquitetura de Invisibilidade Tática:
 * 1. Opera com NotificationChannel em IMPORTANCE_LOW (sem popups, sem som, sem vibração).
 * 2. Notificação compacta no status bar: "VendorOps: Ativo".
 * 3. Ongoing Notification persistente contra clean up de memória.
 * 4. Ao tocar, direciona diretamente para o PrivacyDashboardActivity (Transparência Total).
 * 5. Atualiza o micro-widget Phantom 1x1 e sincroniza o modo com o KeyguardMonitorService.
 */
class SystemStatusOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "vendorops_stealth_channel"
        const val NOTIFICATION_ID = 9001
        @Volatile
        private var activeInstance: SystemStatusOverlayService? = null
        fun getInstance(): SystemStatusOverlayService? = activeInstance
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObserverJob: Job? = null
    private var currentState: DeviceTelemetryState = DeviceTelemetryState()

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createStealthNotificationChannel()

        val initialNotification = buildStealthNotification(currentState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        AuditLogTracker.logAction("SERVICE_START", "Foreground Service de baixo perfil inicializado.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun attachTelemetryManager(telemetryManager: TelemetryStateManager) {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            telemetryManager.stateFlow.collect { state ->
                currentState = state
                KeyguardMonitorService.currentDeviceMode = state.mode

                val notification = buildStealthNotification(state)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)

                // Atualiza o Home Screen Phantom Widget (1x1 LED)
                DigitalTwinAppWidget.updateAllWidgets(applicationContext, state)
            }
        }
    }

    private fun buildStealthNotification(state: DeviceTelemetryState): Notification {
        val isDnd = AuditLogTracker.isDndActive()
        val shortStatus = if (isDnd) {
            "Pausado (DND)"
        } else {
            when (state.mode) {
                DeviceMode.IDLE -> "Ativo"
                DeviceMode.FOCUS -> "Foco (${state.focusedPackage.takeLast(12)})"
                DeviceMode.LIVE -> "Live Streaming"
            }
        }

        // Toque na notificação abre o Privacy Dashboard de conformidade
        val dashboardIntent = Intent(this, PrivacyDashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            dashboardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phantom_led_green)
            .setContentTitle("VendorOps: $shortStatus")
            .setContentText("Sessão corporativa ativa • Toque para ver privacidade")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setShowWhen(false)
            .setAutoCancel(false)
            .build()
    }

    private fun createStealthNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VendorOps Sistema",
                NotificationManager.IMPORTANCE_LOW // Silencioso, sem som, sem heads-up
            ).apply {
                description = "Indicador de presença e conectividade em segundo plano do VendorOps"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        activeInstance = null
        AuditLogTracker.logAction("SERVICE_STOP", "Foreground Service de baixo perfil encerrado.")
        super.onDestroy()
    }
}
