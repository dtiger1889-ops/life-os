package com.example.lifeos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt")
    suspend fun getAll(): List<OutboxEntity>

    @Insert
    suspend fun insert(row: OutboxEntity)

    @Query("DELETE FROM outbox WHERE id = :mutationId")
    suspend fun delete(mutationId: String)
}
