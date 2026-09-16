package com.example.lifeos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LeadDao {
    // "Open" mirrors the casework engine's gather(): done = 0 AND deleted_at IS NULL,
    // ordered by created_at -- same set the desktop `status` CLI shows, so the phone list
    // and a PC-side spot check agree.
    @Query("SELECT * FROM leads WHERE done = 0 AND deletedAt IS NULL ORDER BY createdAt")
    fun observeOpenLeads(): Flow<List<LeadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lead: LeadEntity)

    @Query("SELECT * FROM leads WHERE id = :id")
    suspend fun getById(id: Long): LeadEntity?

    @Query("DELETE FROM leads WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Reconciliation match for a just-synced local create: the newest non-temp (id >= 0),
    // non-deleted row with the same text. Single-user / low-concurrency app -- text match
    // is an acceptable heuristic (see SyncWorker doc comment).
    @Query(
        "SELECT * FROM leads WHERE text = :text AND id >= 0 AND deletedAt IS NULL " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun findOpenMatchByText(text: String): LeadEntity?

    // The other half of reconciliation: local temp (unsynced, negative-id) rows still
    // sitting under this text, to be deleted once findOpenMatchByText confirms the real
    // synced row landed.
    @Query("SELECT * FROM leads WHERE text = :text AND id < 0")
    suspend fun findTempRowsByText(text: String): List<LeadEntity>
}
