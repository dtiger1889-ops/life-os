package com.example.lifeos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One queued phone-initiated mutation awaiting push to the server (POST /api/<db>/push --
 * see server/sync_server.py). `id` is the client-generated UUID, doubling as the mutation's
 * idempotency key server-side (`mutation_id`).
 *
 * Every local edit is a same-transaction write to the entity table AND an insert here
 * (LeadRepository). SyncWorker drains this table: on ack "applied" the row is dropped; on
 * ack "error"/"rejected" it's kept (and logged) for the next sync pass.
 *
 * `recordId` (Long) is for int-autoincrement-keyed tables (leads); `recordKey` (String) is
 * reserved for any future name-keyed table sharing this same outbox (str key_type in
 * sync_server.py's SYNC_TABLES) -- unused today but keeping the column means adding one
 * later is additive, not a schema rework.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val db: String,
    val tbl: String,
    val recordId: Long?,
    val op: String,
    val payload: String,
    val baseVersionUpdatedAt: String?,
    val createdAt: Long,
    val synced: Boolean = false,
    val recordKey: String? = null,
)
