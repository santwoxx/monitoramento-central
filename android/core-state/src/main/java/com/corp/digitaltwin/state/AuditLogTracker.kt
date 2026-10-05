package com.corp.digitaltwin.state

import android.content.Context
import android.content.SharedPreferences
import com.corp.digitaltwin.network.DeviceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

data class AuditEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: String,
    val description: String,
    val details: String = ""
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

    val formattedDateTime: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
}

/**
 * AuditLogTracker - Repositório central de eventos de compliance e transparência local.
 * Mantém o histórico das últimas 50 ações para exibição no PrivacyDashboard e exportação PDF.
 * Também gerencia o modo "DND / Pausa de Monitoramento" de 1 hora.
 */
object AuditLogTracker {
    private const val MAX_LOG_SIZE = 50
    private const val PREFS_NAME = "vendorops_audit_prefs"
    private const val KEY_DND_UNTIL = "dnd_until_epoch_ms"

    private val logQueue = ConcurrentLinkedDeque<AuditEntry>()
    private val _logsFlow = MutableStateFlow<List<AuditEntry>>(emptyList())
    val logsFlow: StateFlow<List<AuditEntry>> = _logsFlow.asStateFlow()

    private var dndUntilEpochMs: Long = 0L
    private val _dndStateFlow = MutableStateFlow(false)
    val dndStateFlow: StateFlow<Boolean> = _dndStateFlow.asStateFlow()

    init {
        // Eventos padrão iniciais do sistema
        logAction("SISTEMA_INICIADO", "VendorOps Kiosk iniciado em conformidade com políticas corporativas.")
        logAction("MODO_INICIAL", "Modo padrão configurado para IDLE (Silent Sync ativo).")
    }

    fun initPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        dndUntilEpochMs = prefs.getLong(KEY_DND_UNTIL, 0L)
        updateDndState()
    }

    fun logAction(eventType: String, description: String, details: String = "") {
        val entry = AuditEntry(
            timestampMs = System.currentTimeMillis(),
            eventType = eventType,
            description = description,
            details = details
        )
        logQueue.addFirst(entry)
        while (logQueue.size > MAX_LOG_SIZE) {
            logQueue.removeLast()
        }
        _logsFlow.value = logQueue.toList()
    }

    fun getRecentLogs(): List<AuditEntry> = logQueue.toList()

    fun isDndActive(): Boolean {
        updateDndState()
        return _dndStateFlow.value
    }

    fun getDndRemainingMinutes(): Int {
        val diff = dndUntilEpochMs - System.currentTimeMillis()
        return if (diff > 0) (diff / (60 * 1000L)).toInt() + 1 else 0
    }

    fun toggleDnd(context: Context, durationMs: Long = 3600_000L): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (isDndActive()) {
            // Desativa DND
            dndUntilEpochMs = 0L
            prefs.edit().remove(KEY_DND_UNTIL).apply()
            updateDndState()
            logAction("PAUSA_CANCELADA", "Pausa de monitoramento cancelada manualmente pelo usuário.")
            return false
        } else {
            // Ativa DND por 1 hora
            dndUntilEpochMs = System.currentTimeMillis() + durationMs
            prefs.edit().putLong(KEY_DND_UNTIL, dndUntilEpochMs).apply()
            updateDndState()
            logAction("PAUSA_ATIVADA", "Monitoramento colocado em modo DO NOT DISTURB (Pausa de 1 hora).")
            return true
        }
    }

    private fun updateDndState() {
        val active = dndUntilEpochMs > System.currentTimeMillis()
        if (_dndStateFlow.value != active) {
            _dndStateFlow.value = active
        }
    }
}
