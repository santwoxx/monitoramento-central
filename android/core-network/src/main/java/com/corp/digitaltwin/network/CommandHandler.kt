package com.corp.digitaltwin.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Interface de despacho de comandos remotos recebidos via canal seguro de WebSocket.
 */
interface CommandHandler {
    val commandEvents: Flow<RemoteCommand>
    suspend fun dispatchCommand(command: RemoteCommand)
    suspend fun acknowledgeCommand(commandId: String, success: Boolean, message: String = "")
}

sealed class RemoteCommand {
    data class SetMode(
        val commandId: String,
        val targetMode: DeviceMode, // IDLE, FOCUS, LIVE
        val timestamp: Long
    ) : RemoteCommand()

    data class CaptureTrigger(
        val commandId: String,
        val reason: String,
        val timestamp: Long
    ) : RemoteCommand()

    data class RequestKeyframe(
        val commandId: String,
        val timestamp: Long
    ) : RemoteCommand()

    data class Unknown(
        val commandId: String,
        val rawCommand: String
    ) : RemoteCommand()
}

enum class DeviceMode(val code: Byte) {
    IDLE(0x00),
    FOCUS(0x01),
    LIVE(0x02);

    companion object {
        fun fromCode(code: Byte): DeviceMode = when(code) {
            0x01.toByte() -> FOCUS
            0x02.toByte() -> LIVE
            else -> IDLE
        }

        fun fromString(str: String): DeviceMode = when(str.uppercase()) {
            "FOCUS" -> FOCUS
            "LIVE" -> LIVE
            else -> IDLE
        }
    }
}

class DefaultCommandHandler : CommandHandler {
    private val _commandEvents = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 64)
    override val commandEvents: Flow<RemoteCommand> = _commandEvents.asSharedFlow()

    override suspend fun dispatchCommand(command: RemoteCommand) {
        _commandEvents.emit(command)
    }

    override suspend fun acknowledgeCommand(commandId: String, success: Boolean, message: String) {
        // Envio do ACK será processado pelo ResilientWebSocketClient
    }
}
