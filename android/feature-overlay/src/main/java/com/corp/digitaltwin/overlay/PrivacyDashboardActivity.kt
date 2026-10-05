package com.corp.digitaltwin.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.corp.digitaltwin.state.AuditEntry
import com.corp.digitaltwin.state.AuditLogTracker
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * PrivacyDashboardActivity - A Prova de Transparência e Controle do Colaborador.
 * 
 * Funcionalidades:
 * 1. Visualização em tempo real dos últimos 50 eventos operacionais do sistema.
 * 2. Botão "Pausar Monitoramento" (Modo DO NOT DISTURB de 1 hora) para cessar envio de mídia.
 * 3. Botão "Exportar Log (PDF)" gerando relatório oficial de compliance via FileProvider.
 * 4. Design moderno, corporativo e 100% programático (Dark Theme).
 */
class PrivacyDashboardActivity : AppCompatActivity() {

    private lateinit var dndButton: Button
    private lateinit var exportButton: Button
    private lateinit var dndStatusTextView: TextView
    private lateinit var logsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuditLogTracker.initPrefs(applicationContext)

        val density = resources.displayMetrics.density

        // Layout Raiz
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 23, 42)) // Slate escuro #0F172A
            setPadding((20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt())
        }

        // Header Superior com Botão Fechar
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (16 * density).toInt())
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val titleView = TextView(this).apply {
            text = "🛡️ PRIVACY & TRANSPARENCY"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(56, 189, 248)) // Ciano
        }
        titleContainer.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Painel de Governança e Auditoria Local do Colaborador"
            textSize = 12f
            setTextColor(Color.rgb(148, 163, 184)) // Slate claro
        }
        titleContainer.addView(subtitleView)
        headerLayout.addView(titleContainer)

        val closeButton = Button(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(50, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
            setOnClickListener { finish() }
        }
        headerLayout.addView(closeButton)
        rootLayout.addView(headerLayout)

        // Card de Status do Sistema
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            val bg = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(Color.rgb(30, 41, 59)) // #1E293B
                setStroke((1 * density).toInt(), Color.argb(40, 56, 189, 248))
            }
            background = bg
        }

        val statusHeader = TextView(this).apply {
            text = "STATUS DE MONITORAMENTO"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(148, 163, 184))
        }
        statusCard.addView(statusHeader)

        dndStatusTextView = TextView(this).apply {
            text = "🟢 Monitoramento Ativo (Modo Corporativo)"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(16, 185, 129))
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        statusCard.addView(dndStatusTextView)
        rootLayout.addView(statusCard)

        // Seção de Controles / Ações (Pausar e Exportar)
        val actionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
        }

        // Botão DND (Pausar Monitoramento)
        dndButton = Button(this).apply {
            text = "⏸️ PAUSAR (1 HORA)"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            val btnBg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.rgb(245, 158, 11)) // Âmbar
            }
            background = btnBg
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener {
                AuditLogTracker.toggleDnd(this@PrivacyDashboardActivity)
                updateDndUI()
            }
        }
        actionsLayout.addView(dndButton)

        // Botão Exportar Log (PDF)
        exportButton = Button(this).apply {
            text = "📄 EXPORTAR PDF"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            val btnBg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.rgb(14, 165, 233)) // Azul Sky
            }
            background = btnBg
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginStart = (8 * density).toInt()
            }
            setOnClickListener { exportAuditLogToPdf() }
        }
        actionsLayout.addView(exportButton)
        rootLayout.addView(actionsLayout)

        // Título da Trilha de Auditoria
        val auditTitle = TextView(this).apply {
            text = "TRILHA DE AUDITORIA LOCAL (ÚLTIMAS 50 AÇÕES)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(203, 213, 225))
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        rootLayout.addView(auditTitle)

        // ScrollView com Lista de Eventos
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        logsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(logsContainer)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        // Observa alterações na lista de logs em tempo real
        lifecycleScope.launch {
            AuditLogTracker.logsFlow.collect { entries ->
                renderLogs(entries)
            }
        }

        lifecycleScope.launch {
            AuditLogTracker.dndStateFlow.collect {
                updateDndUI()
            }
        }
    }

    private fun updateDndUI() {
        val isDnd = AuditLogTracker.isDndActive()
        val remainingMin = AuditLogTracker.getDndRemainingMinutes()

        if (isDnd) {
            dndStatusTextView.text = "⏸️ DO NOT DISTURB ATIVO (Pausa de ${remainingMin}min restantes)"
            dndStatusTextView.setTextColor(Color.rgb(245, 158, 11))
            dndButton.text = "▶️ RETOMAR AGORA"
        } else {
            dndStatusTextView.text = "🟢 Monitoramento Ativo (Modo Corporativo)"
            dndStatusTextView.setTextColor(Color.rgb(16, 185, 129))
            dndButton.text = "⏸️ PAUSAR (1 HORA)"
        }
    }

    private fun renderLogs(entries: List<AuditEntry>) {
        val density = resources.displayMetrics.density
        logsContainer.removeAllViews()

        if (entries.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Nenhum evento registrado ainda."
                textSize = 13f
                setTextColor(Color.rgb(148, 163, 184))
                setPadding(0, (24 * density).toInt(), 0, 0)
                gravity = Gravity.CENTER
            }
            logsContainer.addView(emptyView)
            return
        }

        for (entry in entries) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                val bg = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.rgb(30, 41, 59))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val timeView = TextView(this).apply {
                text = entry.formattedTime
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(56, 189, 248)) // Ciano
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (10 * density).toInt()
                }
            }
            topRow.addView(timeView)

            val tagView = TextView(this).apply {
                text = entry.eventType
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(241, 245, 249))
            }
            topRow.addView(tagView)
            itemLayout.addView(topRow)

            val descView = TextView(this).apply {
                text = entry.description
                textSize = 12.5f
                setTextColor(Color.rgb(203, 213, 225))
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            itemLayout.addView(descView)

            if (entry.details.isNotBlank()) {
                val detailView = TextView(this).apply {
                    text = entry.details
                    textSize = 11f
                    setTextColor(Color.rgb(148, 163, 184))
                    setPadding(0, (2 * density).toInt(), 0, 0)
                }
                itemLayout.addView(detailView)
            }

            logsContainer.addView(itemLayout)
        }
    }

    /**
     * Gera relatório de auditoria local em PDF formatado e compartilha via FileProvider.
     */
    private fun exportAuditLogToPdf() {
        try {
            val entries = AuditLogTracker.getRecentLogs()
            val pdfDocument = PdfDocument()

            // Dimensões A4 padrão: 595 x 842 pontos
            val pageWidth = 595
            val pageHeight = 842
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val paint = Paint().apply { isAntiAlias = true }

            // 1. Cabeçalho do Documento
            paint.color = Color.rgb(15, 23, 42) // Slate escuro
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), 90f, paint)

            paint.color = Color.rgb(56, 189, 248) // Ciano
            paint.textSize = 18f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("VENDEROPS - RELATÓRIO DE AUDITORIA & PRIVACIDADE", 30f, 40f, paint)

            paint.color = Color.WHITE
            paint.textSize = 10f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            canvas.drawText("Gerado em: $nowStr | Terminal: Local Device | Governança: LGPD Compliance", 30f, 65f, paint)

            // 2. Metadados do Aparelho
            var currentY = 120f
            paint.color = Color.rgb(30, 41, 59)
            paint.textSize = 12f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("RESUMO OPERACIONAL", 30f, currentY, paint)
            currentY += 18f

            paint.color = Color.DKGRAY
            paint.textSize = 9.5f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            val dndStatus = if (AuditLogTracker.isDndActive()) "ATIVO (Em Pausa)" else "INATIVO (Monitoramento Normal)"
            canvas.drawText("Total de Ações Registradas: ${entries.size} | Modo DND: $dndStatus", 30f, currentY, paint)
            currentY += 24f

            // Linha divisória
            paint.color = Color.LTGRAY
            paint.strokeWidth = 1f
            canvas.drawLine(30f, currentY, (pageWidth - 30).toFloat(), currentY, paint)
            currentY += 20f

            // 3. Tabela de Registros
            paint.color = Color.BLACK
            paint.textSize = 10f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("HORÁRIO", 30f, currentY, paint)
            canvas.drawText("EVENTO", 100f, currentY, paint)
            canvas.drawText("DESCRIÇÃO E CONTEXTO", 220f, currentY, paint)
            currentY += 15f

            paint.color = Color.LTGRAY
            canvas.drawLine(30f, currentY, (pageWidth - 30).toFloat(), currentY, paint)
            currentY += 15f

            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textSize = 8.5f

            for (entry in entries.take(35)) { // Limita a primeira página para manter formatação limpa
                if (currentY > pageHeight - 50) break

                paint.color = Color.DKGRAY
                canvas.drawText(entry.formattedTime, 30f, currentY, paint)

                paint.color = Color.BLACK
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText(entry.eventType.take(18), 100f, currentY, paint)

                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                paint.color = Color.rgb(51, 65, 85)
                val desc = if (entry.description.length > 55) entry.description.take(52) + "..." else entry.description
                canvas.drawText(desc, 220f, currentY, paint)

                currentY += 18f
            }

            // Rodapé
            paint.color = Color.GRAY
            paint.textSize = 8f
            canvas.drawText("Documento oficial gerado localmente pelo VendorOps Kiosk. Acesso estrito para auditoria do colaborador.", 30f, pageHeight - 20f, paint)

            pdfDocument.finishPage(page)

            // Salva no cache da aplicação
            val pdfFile = File(cacheDir, "vendorops_trilha_auditoria.pdf")
            val fos = FileOutputStream(pdfFile)
            pdfDocument.writeTo(fos)
            fos.close()
            pdfDocument.close()

            AuditLogTracker.logAction("EXPORTACAO_PDF", "Relatório de auditoria exportado com sucesso (${entries.size} itens).")

            // Compartilha ou abre via Intent com FileProvider
            val contentUri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                pdfFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "VendorOps - Trilha de Auditoria")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Exportar Trilha de Auditoria"))

        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao gerar PDF de auditoria: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
