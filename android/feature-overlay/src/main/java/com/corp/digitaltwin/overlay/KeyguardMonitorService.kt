package com.corp.digitaltwin.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.corp.digitaltwin.network.DeviceMode
import com.corp.digitaltwin.state.AuditLogTracker

/**
 * KeyguardMonitorService - Interceptador de Desbloqueio e Camada de Interação Contextual Ambient.
 * 
 * Funcionalidades:
 * 1. Monitora transições de tela e desbloqueio via AccessibilityService e BroadcastReceiver de sistema.
 * 2. Lógica "First Unlock of the Session": Se o aparelho esteve bloqueado ou em background por mais
 *    de 5 minutos (ou no primeiro desbloqueio), projeta uma pill flutuante no topo por 3 segundos.
 * 3. Notificação invisível/ambient: O usuário vê "VendorOps: Sessão Ativa | Modo: [MODO] | [Toque para ver status]".
 * 4. Toque direciona imediatamente para o PrivacyDashboardActivity (Transparência).
 */
class KeyguardMonitorService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyguardMonitorService"
        private const val FIVE_MINUTES_MS = 5 * 60 * 1000L
        private const val OVERLAY_DISMISS_DELAY_MS = 3000L

        @Volatile
        var currentDeviceMode: DeviceMode = DeviceMode.IDLE
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    private var lastLockTimestamp: Long = 0L
    private var isFirstUnlockOfSession: Boolean = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastLockTimestamp = System.currentTimeMillis()
                    Log.d(TAG, "Tela desligada/bloqueada registrada.")
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "KEYGUARD_UNLOCKED interceptado via ACTION_USER_PRESENT.")
                    handleKeyguardUnlocked()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "KeyguardMonitorService conectado via Accessibility.")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.DEFAULT
        }
        serviceInfo = info

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        // Se o serviço iniciou com tela já ativa, marca início
        if (lastLockTimestamp == 0L) {
            lastLockTimestamp = System.currentTimeMillis()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Intercepta alterações de estado de janelas associadas ao Keyguard do SystemUI
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            val cls = event.className?.toString() ?: ""
            if (pkg.contains("systemui") && cls.contains("Keyguard", ignoreCase = true)) {
                Log.d(TAG, "Keyguard Window Transition detectada: $cls")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "KeyguardMonitorService interrompido.")
    }

    private fun handleKeyguardUnlocked() {
        val now = System.currentTimeMillis()
        val elapsedSinceLock = if (lastLockTimestamp > 0) (now - lastLockTimestamp) else FIVE_MINUTES_MS

        val shouldTriggerOverlay = isFirstUnlockOfSession || (elapsedSinceLock >= FIVE_MINUTES_MS)

        AuditLogTracker.logAction(
            "KEYGUARD_UNLOCKED",
            "Desbloqueio de tela interceptado pelo KeyguardMonitorService.",
            "Tempo bloqueado: ${elapsedSinceLock / 1000}s | FirstUnlock: $isFirstUnlockOfSession"
        )

        if (shouldTriggerOverlay) {
            isFirstUnlockOfSession = false
            lastLockTimestamp = now
            showContextualUnlockBanner()
        }
    }

    private fun showContextualUnlockBanner() {
        mainHandler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "Sem permissão SYSTEM_ALERT_WINDOW para exibir contextual overlay.")
                    return@post
                }

                removeOverlayView()

                val density = resources.displayMetrics.density
                val mode = currentDeviceMode

                // Constrói a View flutuante programaticamente com design glassmorphic
                val bannerLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())

                    // Background arredondado glassmorphism
                    val bg = GradientDrawable().apply {
                        cornerRadius = 24 * density
                        setColor(Color.argb(230, 15, 23, 42)) // Slate escuro 90% opaco
                        setStroke((1 * density).toInt(), Color.argb(60, 56, 189, 248)) // Ciano sutil
                    }
                    background = bg
                }

                // LED indicador de estado
                val ledView = ImageView(this).apply {
                    val ledRes = when (mode) {
                        DeviceMode.IDLE -> R.drawable.ic_phantom_led_green
                        DeviceMode.FOCUS -> R.drawable.ic_phantom_led_amber
                        DeviceMode.LIVE -> R.drawable.ic_phantom_led_red
                    }
                    setImageResource(ledRes)
                    val size = (16 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (10 * density).toInt()
                    }
                }
                bannerLayout.addView(ledView)

                // Texto informativo
                val textView = TextView(this).apply {
                    text = "VendorOps: Sessão Ativa | Modo: ${mode.name} | [Toque para ver status]"
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                }
                bannerLayout.addView(textView)

                // Toque abre o Privacy Dashboard
                bannerLayout.setOnClickListener {
                    removeOverlayView()
                    val intent = Intent(this@KeyguardMonitorService, PrivacyDashboardActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(intent)
                }

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (42 * density).toInt()
                }

                windowManager?.addView(bannerLayout, layoutParams)
                overlayView = bannerLayout

                // Auto-dismiss após 3 segundos
                dismissRunnable = Runnable {
                    removeOverlayView()
                }
                mainHandler.postDelayed(dismissRunnable!!, OVERLAY_DISMISS_DELAY_MS)

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao projetar contextual unlock banner: ${e.message}", e)
            }
        }
    }

    private fun removeOverlayView() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        removeOverlayView()
        super.onDestroy()
    }
}
