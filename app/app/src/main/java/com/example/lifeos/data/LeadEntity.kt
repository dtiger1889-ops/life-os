package com.example.lifeos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Mirrors the reference "casework" engine's `leads` table (example/casework/casework.py
 * init_db() + its sync columns). Room is the on-device source of truth -- UI reads this
 * table via Flow only.
 *
 * `id` is NOT auto-generated: a locally-created lead is assigned a negative temp id (see
 * LeadRepository.newTempId()) so it never collides with a real server-assigned positive
 * autoincrement id. Once the create mutation syncs, the sync worker replaces the temp row
 * with the real pulled row (real id) and deletes the temp one.
 */
@Entity(tableName = "leads")
data class LeadEntity(
    @PrimaryKey val id: Long,
    val text: String,
    val caseId: Long?,
    val urgency: String,
    val done: Boolean,
    val createdAt: String,
    val doneAt: String?,
    val updatedAt: String,
    val updatedBy: String,
    val deletedAt: String?,
    val serverSeq: Long?,
)
