package com.corp.digitaltwin.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entidade para o Outbox Pattern.
 * Garante que qualquer pacote de telemetria ou metadados gerado durante períodos de
 * desconexão seja persistido em armazenamento local (SQLite/Room) e transmitido
 * em estrita ordem cronológica (FIFO) assim que o canal de transporte for reestabelecido.
 */
@Entity(tableName = "telemetry_outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val messageType: Byte,
    val sequenceNumber: Long,
    val payload: ByteArray,
    val retryCount: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OutboxEntity

        if (id != other.id) return false
        if (timestamp != other.timestamp) return false
        if (messageType != other.messageType) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (!payload.contentEquals(other.payload)) return false
        if (retryCount != other.retryCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + messageType.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + retryCount
        return result
    }
}
