package com.corp.digitaltwin.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entity: OutboxEntity): Long

    @Query("SELECT * FROM telemetry_outbox ORDER BY id ASC LIMIT :batchSize")
    suspend fun getOldestBatch(batchSize: Int = 50): List<OutboxEntity>

    @Query("DELETE FROM telemetry_outbox WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM telemetry_outbox WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM telemetry_outbox")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM telemetry_outbox")
    suspend fun getPendingCount(): Int

    @Query("UPDATE telemetry_outbox SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: Long)

    @Query("DELETE FROM telemetry_outbox WHERE createdAtEpochMs < :cutoffEpochMs")
    suspend fun pruneOldRecords(cutoffEpochMs: Long): Int
}
