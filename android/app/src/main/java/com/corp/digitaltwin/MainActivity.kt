package com.corp.digitaltwin

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.corp.digitaltwin.overlay.SystemStatusOverlayService

/**
 * MainActivity - Painel de Controle e Autorização do Digital Twin no Dispositivo Android.
 * 
 * Interface 100% programática (não depende de layouts XML externos).
 * Permite ao operador/técnico:
 * 1. Definir o IP do PC na rede Wi-Fi (ex: 192.168.1.100) e o Tag do vendedor (ex: VND1).
 * 2. Conceder permissões de MediaProjection, Áudio, Notificações e Overlay.
 * 3. Iniciar e Parar o DigitalTwinCoordinator em tempo de execução.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var coordinator: DigitalTwinCoordinator
    private lateinit var mpm: MediaProjectionManager

    private lateinit var ipEditText: EditText
    private lateinit var tagEditText: EditText
    private lateinit var statusTextView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var isStreaming = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startDigitalTwinPipelines(result.resultCode, result.data!!)
        } else {
            statusTextView.text = "Status: Permissão de tela negada pelo usuário."
            statusTextView.setTextColor(Color.RED)
            startButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Carrega configurações salvas
        val prefs = getSharedPreferences("digital_twin_config", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("server_ip", "ws://192.168.1.100:5000")
            ?.replace("ws://", "")
            ?.replace(":5000", "") ?: "192.168.1.100"
        val savedTag = prefs.getString("device_tag", "VND1") ?: "VND1"

        // Constrói interface programática
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 15, 25)) // Dark Theme
            setPadding(48, 64, 48, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val titleView = TextView(this).apply {
            text = "🛡️ DIGITAL TWIN CLIENT"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(56, 189, 248)) // Ciano
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Streaming de Baixa Latência (<200ms) Direct-to-Local Wi-Fi"
            textSize = 12f
            setTextColor(Color.rgb(148, 163, 184))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        rootLayout.addView(subtitleView)

        // Campo IP do PC
        val ipLabel = TextView(this).apply {
            text = "IP do PC Local (Porta 5000):"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        rootLayout.addView(ipLabel)

        ipEditText = EditText(this).apply {
            setText(savedIp)
            hint = "ex: 192.168.1.100"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 41, 59))
            setPadding(24, 24, 24, 24)
        }
        rootLayout.addView(ipEditText)

        // Campo DeviceTag
        val tagLabel = TextView(this).apply {
            text = "Tag do Terminal / Vendedor (4 Caracteres):"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 0)
        }
        rootLayout.addView(tagLabel)

        tagEditText = EditText(this).apply {
            setText(savedTag)
            hint = "ex: VND1"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 41, 59))
            setPadding(24, 24, 24, 24)
        }
        rootLayout.addView(tagEditText)

        // Status
        statusTextView = TextView(this).apply {
            text = "Status: Aguardando inicialização"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(203, 213, 225))
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 48)
        }
        rootLayout.addView(statusTextView)

        // Botão Iniciar
        startButton = Button(this).apply {
            text = "▶️ INICIAR TRANSMISSÃO"
            setBackgroundColor(Color.rgb(16, 185, 129)) // Esmeralda
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 32, 0, 32)
            setOnClickListener { requestPermissionsAndStart() }
        }
        rootLayout.addView(startButton)

        // Botão Parar
        stopButton = Button(this).apply {
            text = "⏹️ PARAR TRANSMISSÃO"
            setBackgroundColor(Color.rgb(239, 68, 68)) // Vermelho
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 32, 0, 32)
            isEnabled = false
            setOnClickListener { stopDigitalTwin() }
        }
        rootLayout.addView(stopButton)

        setContentView(rootLayout)
    }

    private fun requestPermissionsAndStart() {
        val rawIp = ipEditText.text.toString().trim()
        val rawTag = tagEditText.text.toString().trim().take(4).padEnd(4, '_').uppercase()

        if (rawIp.isEmpty()) {
            Toast.makeText(this, "Digite o IP do PC local", Toast.LENGTH_SHORT).show()
            return
        }

        // Salva preferências
        getSharedPreferences("digital_twin_config", Context.MODE_PRIVATE)
            .edit()
            .putString("server_ip", "ws://$rawIp:5000")
            .putString("device_tag", rawTag)
            .apply()

        // Checa permissões runtime (Áudio e Notificações no Android 13+)
        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 101)
            return
        }

        // Permissão de Overlay (Marca d'água visual)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Conceda permissão de sobreposição para a marca d'água", Toast.LENGTH_LONG).show()
            return
        }

        // Lança o diálogo oficial de captura de tela
        startButton.isEnabled = false
        val captureIntent = mpm.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startDigitalTwinPipelines(resultCode: Int, data: Intent) {
        val rawIp = ipEditText.text.toString().trim()
        val rawTag = tagEditText.text.toString().trim().take(4).padEnd(4, '_').uppercase()

        // 1. Inicia Foreground Service
        val serviceIntent = Intent(this, SystemStatusOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 2. Inicializa o Orquestrador
        coordinator = DigitalTwinCoordinator(
            context = applicationContext,
            deviceId = rawTag,
            initialServerUrl = "ws://$rawIp:5000"
        )
        coordinator.start()

        // 3. Conecta a captura de tela e áudio
        coordinator.attachMediaProjection(resultCode, data, mpm)

        isStreaming = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        ipEditText.isEnabled = false
        tagEditText.isEnabled = false

        statusTextView.text = "Status: TRANSMITINDO AO VIVO\nSala: device_$rawTag ➔ $rawIp:5000"
        statusTextView.setTextColor(Color.rgb(16, 185, 129))
    }

    private fun stopDigitalTwin() {
        if (isStreaming) {
            coordinator.stop()
            stopService(Intent(this, SystemStatusOverlayService::class.java))

            isStreaming = false
            startButton.isEnabled = true
            stopButton.isEnabled = false
            ipEditText.isEnabled = true
            tagEditText.isEnabled = true

            statusTextView.text = "Status: Desconectado"
            statusTextView.setTextColor(Color.rgb(203, 213, 225))
        }
    }

    override fun onDestroy() {
        if (isStreaming) {
            coordinator.stop()
        }
        super.onDestroy()
    }
}
