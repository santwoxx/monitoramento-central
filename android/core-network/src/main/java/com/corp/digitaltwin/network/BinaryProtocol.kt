package com.corp.digitaltwin.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo Binário de Baixa Latência (Wire Protocol) para Rede Local Wi-Fi.
 * 
 * Cabeçalho Fixo (20 bytes):
 * [0..0]   : Magic Byte (0xD7)
 * [1..1]   : Versão do Protocolo (0x01)
 * [2..2]   : Tipo de Mensagem (0x01=Hello, 0x02=Telemetry, 0x03=VideoNAL, 0x04=Audio, 0x05=Command, 0x06=Ack, 0x07=Heartbeat)
 * [3..3]   : Flags (Bit 0: IsFragment, Bit 1: OutboxReplay, Bit 2: KeyFrame)
 * [4..7]   : Sequence Number (UInt32, Big-Endian)
 * [8..15]  : Timestamp em ms (UInt64 Epoch, Big-Endian)
 * [16..19] : Tamanho do Payload (UInt32, Big-Endian)
 * 
 * Estrutura do Payload:
 * [20..23] : DeviceID de 4 Caracteres ASCII (ex: "VND1", "VND2") -> Roteamento Imediato de Salas no Gateway local
 * [24..N]  : Dados da Mensagem (NAL Chunks, Audio AAC, Telemetria)
 */
object BinaryProtocol {
    const val MAGIC_BYTE: Byte = 0xD7.toByte()
    const val PROTOCOL_VERSION: Byte = 0x01.toByte()
    const val HEADER_SIZE: Int = 20
    const val DEVICE_TAG_SIZE: Int = 4 // 4 bytes ASCII fixos para a Sala do Operador (ex: "VND1")

    // Tipos de Mensagem
    const val TYPE_HELLO: Byte = 0x01
    const val TYPE_TELEMETRY: Byte = 0x02
    const val TYPE_VIDEO_NAL: Byte = 0x03
    const val TYPE_AUDIO: Byte = 0x04
    const val TYPE_COMMAND: Byte = 0x05
    const val TYPE_COMMAND_ACK: Byte = 0x06
    const val TYPE_HEARTBEAT: Byte = 0x07
    const val TYPE_SYNC_TICK: Byte = 0x08

    // Audio Context (Contextual Audio Tagging - 1 byte)
    object AudioContext {
        const val VOICE_PRIMARY: Byte = 0x01     // Voz de cliente/vendedor em foco
        const val MEDIA_BACKGROUND: Byte = 0x02  // Música de app (Spotify, etc.)
        const val UI_FEEDBACK: Byte = 0x03       // Beeps de caixa / UI
        const val SILENCE: Byte = 0x04           // Silêncio estrutural para A/V sync
    }

    // Flags
    const val FLAG_NONE: Byte = 0x00
    const val FLAG_IS_FRAGMENT: Byte = 0x01
    const val FLAG_OUTBOX_REPLAY: Byte = 0x02
    const val FLAG_KEYFRAME: Byte = 0x04

    data class Frame(
        val messageType: Byte,
        val flags: Byte = FLAG_NONE,
        val sequenceNumber: Long,
        val timestamp: Long = System.currentTimeMillis(),
        val deviceTag: String = "VND1", // Ex: "VND1"
        val payload: ByteArray
    )

    data class TelemetryPayload(
        val deviceId: String,
        val stateMode: Byte, // 0 = IDLE, 1 = FOCUS, 2 = LIVE
        val batteryPct: Int,
        val isCharging: Boolean,
        val focusedPackage: String,
        val cpuUsagePct: Float,
        val ramUsageMb: Long
    )

    /**
     * Garante que o DeviceTag tenha exatamente 4 bytes ASCII (ex: "VND1")
     */
    fun formatDeviceTag(deviceId: String): ByteArray {
        val sanitized = deviceId.filter { it.isLetterOrDigit() }.take(4).padEnd(4, '_')
        return sanitized.toByteArray(Charsets.US_ASCII)
    }

    fun parseDeviceTag(data: ByteArray, offset: Int): String {
        return try {
            String(data, offset, DEVICE_TAG_SIZE, Charsets.US_ASCII).trim()
        } catch (_: Exception) {
            "VND1"
        }
    }

    /**
     * Empacota o frame binário com os 20 bytes de cabeçalho + 4 bytes do DeviceTag ASCII + sub-payload.
     */
    fun encodeFrame(frame: Frame): ByteArray {
        val tagBytes = formatDeviceTag(frame.deviceTag)
        val totalLength = HEADER_SIZE + DEVICE_TAG_SIZE + frame.payload.size
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)

        // Cabeçalho de 20 Bytes
        buffer.put(MAGIC_BYTE)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(frame.messageType)
        buffer.put(frame.flags)
        buffer.putInt(frame.sequenceNumber.toInt())
        buffer.putLong(frame.timestamp)
        buffer.putInt(DEVICE_TAG_SIZE + frame.payload.size)

        // Prefixo de 4 Bytes no Payload: DeviceTag ASCII (ex: "VND1")
        buffer.put(tagBytes)

        // Dados do payload
        buffer.put(frame.payload)
        return buffer.array()
    }

    fun decodeFrame(data: ByteArray): Frame? {
        if (data.size < HEADER_SIZE + DEVICE_TAG_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.get()
        if (magic != MAGIC_BYTE) return null
        val version = buffer.get()
        if (version != PROTOCOL_VERSION) return null
        val messageType = buffer.get()
        val flags = buffer.get()
        val seq = buffer.getInt().toLong() and 0xFFFFFFFFL
        val timestamp = buffer.getLong()
        val payloadLen = buffer.getInt()

        if (buffer.remaining() < payloadLen || payloadLen < DEVICE_TAG_SIZE) return null
        val tagBytes = ByteArray(DEVICE_TAG_SIZE)
        buffer.get(tagBytes)
        val deviceTag = String(tagBytes, Charsets.US_ASCII)

        val dataLen = payloadLen - DEVICE_TAG_SIZE
        val payload = ByteArray(dataLen)
        buffer.get(payload)

        return Frame(
            messageType = messageType,
            flags = flags,
            sequenceNumber = seq,
            timestamp = timestamp,
            deviceTag = deviceTag,
            payload = payload
        )
    }

    fun encodeTelemetry(telemetry: TelemetryPayload): ByteArray {
        val devIdBytes = telemetry.deviceId.toByteArray(Charsets.UTF_8)
        val pkgBytes = telemetry.focusedPackage.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 2 + devIdBytes.size + 1 + 1 + 1 + 2 + pkgBytes.size + 4 + 8)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.put(telemetry.stateMode)
        buffer.putShort(devIdBytes.size.toShort())
        buffer.put(devIdBytes)
        buffer.put(telemetry.batteryPct.toByte())
        buffer.put(if (telemetry.isCharging) 1.toByte() else 0.toByte())
        buffer.putShort(pkgBytes.size.toShort())
        buffer.put(pkgBytes)
        buffer.putFloat(telemetry.cpuUsagePct)
        buffer.putLong(telemetry.ramUsageMb)
        return buffer.array()
    }

    fun decodeTelemetry(bytes: ByteArray): TelemetryPayload? {
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val stateMode = buffer.get()
            val devIdLen = buffer.getShort().toInt()
            val devIdBytes = ByteArray(devIdLen)
            buffer.get(devIdBytes)
            val deviceId = String(devIdBytes, Charsets.UTF_8)
            val batteryPct = buffer.get().toInt() and 0xFF
            val isCharging = buffer.get() == 1.toByte()
            val pkgLen = buffer.getShort().toInt()
            val pkgBytes = ByteArray(pkgLen)
            buffer.get(pkgBytes)
            val focusedPackage = String(pkgBytes, Charsets.UTF_8)
            val cpuUsagePct = buffer.getFloat()
            val ramUsageMb = buffer.getLong()

            TelemetryPayload(
                deviceId = deviceId,
                stateMode = stateMode,
                batteryPct = batteryPct,
                isCharging = isCharging,
                focusedPackage = focusedPackage,
                cpuUsagePct = cpuUsagePct,
                ramUsageMb = ramUsageMb
            )
        } catch (_: Exception) {
            null
        }
    }
}
