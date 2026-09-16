package com.example.lifeos.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Offline-first repository (Google's repository pattern): Room is the on-device source of
 * truth; every local edit is a same-transaction write to the entity table AND an outbox
 * insert. UI reads only `openLeads()`'s Flow. After every local edit this schedules an
 * immediate sync attempt (WorkManager, see SyncScheduler) -- a no-op if the network
 * constraint isn't met yet; the outbox just accumulates until it is.
 *
 * Ops mirror the casework engine's SYNC_TABLES config for `leads` exactly (create / update /
 * delete / done -- a dedicated named op, not a generic update, matching done_lead()'s
 * one-directional semantics in example/casework/casework.py).
 */
class LeadRepository(private val context: Context) {
    private val db = LifeOsDatabase.getInstance(context)
    private val leadDao = db.leadDao()
    private val outboxDao = db.outboxDao()

    fun openLeads(): Flow<List<LeadEntity>> = leadDao.observeOpenLeads()

    /** Negative, effectively-unique local id for a not-yet-synced row (never collides with a
     * real positive server autoincrement id). Replaced by the real row once sync reconciles it. */
    private fun newTempId(): Long = -System.nanoTime()

    private fun nowIsoLocal(): String =
        LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

    suspend fun addLead(text: String, urgency: String, caseId: Long?) {
        withContext(Dispatchers.IO) {
            val now = nowIsoLocal()
            val tempId = newTempId()
            val entity = LeadEntity(
                id = tempId, text = text, caseId = caseId, urgency = urgency, done = false,
                createdAt = now, doneAt = null, updatedAt = now, updatedBy = "phone",
                deletedAt = null, serverSeq = null,
            )
            val payload = JSONObject().apply {
                put("text", text)
                put("case_id", caseId ?: JSONObject.NULL)
                put("urgency", urgency)
            }
            val outbox = OutboxEntity(
                id = UUID.randomUUID().toString(), db = "casework", tbl = "leads",
                recordId = tempId, op = "create", payload = payload.toString(),
                baseVersionUpdatedAt = null, createdAt = System.currentTimeMillis(),
            )
            db.withTransaction {
                leadDao.upsert(entity)
                outboxDao.insert(outbox)
            }
        }
        SyncScheduler.scheduleImmediate(context)
    }

    /** Field-subset update. `lead` must be a synced row (id >= 0) -- callers gate on that
     * (see LeadsScreen: unsynced/temp rows disable edit/done/delete until the create lands). */
    suspend fun updateLead(lead: LeadEntity, fields: Map<String, Any?>) {
        withContext(Dispatchers.IO) {
            val now = nowIsoLocal()
            val updated = lead.copy(
                text = fields["text"] as? String ?: lead.text,
                caseId = if (fields.containsKey("case_id")) fields["case_id"] as? Long else lead.caseId,
                urgency = fields["urgency"] as? String ?: lead.urgency,
                updatedAt = now,
                updatedBy = "phone",
            )
            val payload = JSONObject()
            for ((k, v) in fields) {
                payload.put(k, v ?: JSONObject.NULL)
            }
            val outbox = OutboxEntity(
                id = UUID.randomUUID().toString(), db = "casework", tbl = "leads",
                recordId = lead.id, op = "update", payload = payload.toString(),
                baseVersionUpdatedAt = lead.updatedAt, createdAt = System.currentTimeMillis(),
            )
            db.withTransaction {
                leadDao.upsert(updated)
                outboxDao.insert(outbox)
            }
        }
        SyncScheduler.scheduleImmediate(context)
    }

    /** One-directional, matching the engine's done_lead(): there is no "un-done" path through
     * this op. The open-leads query filters done=0 anyway, so a done lead simply leaves the list. */
    suspend fun markDone(lead: LeadEntity) {
        withContext(Dispatchers.IO) {
            val now = nowIsoLocal()
            val updated = lead.copy(done = true, doneAt = now, updatedAt = now, updatedBy = "phone")
            val outbox = OutboxEntity(
                id = UUID.randomUUID().toString(), db = "casework", tbl = "leads",
                recordId = lead.id, op = "done", payload = "{}",
                baseVersionUpdatedAt = lead.updatedAt, createdAt = System.currentTimeMillis(),
            )
            db.withTransaction {
                leadDao.upsert(updated)
                outboxDao.insert(outbox)
            }
        }
        SyncScheduler.scheduleImmediate(context)
    }

    suspend fun deleteLead(lead: LeadEntity) {
        withContext(Dispatchers.IO) {
            val now = nowIsoLocal()
            val tombstoned = lead.copy(deletedAt = now, updatedAt = now, updatedBy = "phone")
            val outbox = OutboxEntity(
                id = UUID.randomUUID().toString(), db = "casework", tbl = "leads",
                recordId = lead.id, op = "delete", payload = "{}",
                baseVersionUpdatedAt = lead.updatedAt, createdAt = System.currentTimeMillis(),
            )
            db.withTransaction {
                leadDao.upsert(tombstoned)
                outboxDao.insert(outbox)
            }
        }
        SyncScheduler.scheduleImmediate(context)
    }
}
