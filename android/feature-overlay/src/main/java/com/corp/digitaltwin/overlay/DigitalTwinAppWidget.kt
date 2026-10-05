package com.corp.digitaltwin.overlay

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.corp.digitaltwin.network.DeviceMode
import com.corp.digitaltwin.state.AuditLogTracker
import com.corp.digitaltwin.state.DeviceTelemetryState

/**
 * DigitalTwinAppWidget - O "Phantom Widget" 1x1 de Baixa Presença.
 * 
 * Características:
 * 1. Tamanho micro 1x1: ocupa apenas um ícone na grade da tela inicial.
 * 2. LED Minimalista:
 *    - Verde: IDLE (Telemetria leve / Silent Sync).
 *    - Amarelo/Âmbar: FOCUS (Monitorando app corporativo específico).
 *    - Vermelho: LIVE (Streaming em tempo real ativo).
 * 3. Sem textos ou botões poluentes. Apenas um indicador sutil de presença.
 * 4. Ao tocar, abre diretamente o PrivacyDashboardActivity com auditoria e log local.
 */
class DigitalTwinAppWidget : AppWidgetProvider() {

    companion object {
        fun updateAllWidgets(context: Context, state: DeviceTelemetryState) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, DigitalTwinAppWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (widgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId, state)
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: DeviceTelemetryState
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_phantom)

            val ledDrawableRes = when {
                AuditLogTracker.isDndActive() -> R.drawable.ic_phantom_led_amber
                state.mode == DeviceMode.LIVE -> R.drawable.ic_phantom_led_red
                state.mode == DeviceMode.FOCUS -> R.drawable.ic_phantom_led_amber
                else -> R.drawable.ic_phantom_led_green
            }

            views.setImageViewResource(R.id.phantom_widget_led, ledDrawableRes)

            // Toque no micro-widget abre o Privacy Dashboard de transparência
            val dashboardIntent = Intent(context, PrivacyDashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                dashboardIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.phantom_widget_root, pendingIntent)
            views.setOnClickPendingIntent(R.id.phantom_widget_led, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, widgetId, DeviceTelemetryState())
        }
    }
}
