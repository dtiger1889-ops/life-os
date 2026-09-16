package com.example.lifeos.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SyncWorker"

/**
 * Sync engine. One run: /health probe -> for the "casework" db -> push its outbox rows ->
 * pull since its own cursor -> upsert into Room -> reconcile locally-created temp rows
 * against the real rows the pull just brought down.
 *
 * Only one db ships in this starter kit, but syncCasework() is kept as its own function
 * (rather than inlined into doWork()) with a per-db cursor key (SyncPrefs.getCursor/setCursor)
 * so adding a second dashboard's db later is "add another syncXxx() + another cursor key,"
 * matching the reference deployment's shape, not a rewrite.
 *
 * Scheduled by SyncScheduler: on app foreground, after every local edit (immediate, dedup'd
 * via ExistingWorkPolicy.KEEP), and periodically every 15 min -- never a resident/foreground
 * service. NetworkType.CONNECTED constraint + Result.retry() gives WorkManager's own backoff;
 * server-off/unreachable just means the outbox accumulates and this returns retry().
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (SyncApi.health(applicationContext) == null) {
                Log.i(TAG, "Health probe failed -- server unreachable, will retry")
                return Result.retry()
            }
            val database = LifeOsDatabase.getInstance(applicationContext)
            val outboxDao = database.outboxDao()
            val prefs = SyncPrefs(applicationContext)

            if (syncCasework(database, outboxDao, prefs)) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Sync run failed", e)
            Result.retry()
        }
    }

    // --------------------------------------------------------------------
    // Shared push helper -- returns null on network failure (caller should retry the whole
    // worker); otherwise the list of (outbox row, server ack) pairs for applied/rejected
    // mutations, for the caller's own reconciliation.
    // --------------------------------------------------------------------
    private suspend fun pushPending(
        dbName: String,
        allOutbox: List<OutboxEntity>,
        outboxDao: OutboxDao,
    ): List<Pair<OutboxEntity, JSONObject>>? {
        val pending = allOutbox.filter { it.db == dbName }
        if (pending.isEmpty()) return emptyList()

        val mutations = JSONArray()
        for (row in pending) {
            val mutation = JSONObject()
            mutation.put("mutation_id", row.id)
            mutation.put("table", row.tbl)
            mutation.put("op", row.op)
            // Int-keyed tables (leads) send recordId (null on create, a real id otherwise);
            // any future name-keyed table would send recordKey instead.
            val recordIdValue: Any = when {
                row.recordKey != null -> row.recordKey
                row.recordId != null && row.recordId >= 0 -> row.recordId
                else -> JSONObject.NULL
            }
            mutation.put("record_id", recordIdValue)
            mutation.put("payload", JSONObject(row.payload))
            mutation.put("base_version_updated_at", row.baseVersionUpdatedAt ?: JSONObject.NULL)
            mutations.put(mutation)
        }

        val pushResponse = SyncApi.push(applicationContext, dbName, mutations)
        if (pushResponse == null) {
            Log.w(TAG, "[$dbName] Push failed (network) -- will retry")
            return null
        }
        val acks = pushResponse.optJSONArray("acks") ?: JSONArray()
        val results = mutableListOf<Pair<OutboxEntity, JSONObject>>()
        for (i in 0 until acks.length()) {
            val ack = acks.getJSONObject(i)
            val mutationId = ack.optString("mutation_id")
            val status = ack.optString("status")
            val row = pending.find { it.id == mutationId } ?: continue
            when (status) {
                "applied" -> outboxDao.delete(row.id)
                "rejected" -> {
                    // Permanent: the server rejects non-create ops against rows that no longer
                    // exist (or never did). Retrying can never succeed, so the mutation must be
                    // dropped here or it retries forever.
                    Log.w(
                        TAG,
                        "[$dbName] Outbox ${row.id} (${row.tbl}/${row.op}) permanently rejected -- " +
                            "dropping: reason=${ack.optString("reason", ack.optString("note"))}",
                    )
                    outboxDao.delete(row.id)
                }
                else -> Log.w(
                    TAG,
                    "[$dbName] Outbox ${row.id} (${row.tbl}/${row.op}) not applied: " +
                        "status=$status reason=${ack.optString("reason", ack.optString("note"))} -- will retry",
                )
            }
            results.add(row to ack)
        }
        return results
    }

    // --------------------------------------------------------------------
    // Casework: this starter kit's one reference dashboard db.
    // --------------------------------------------------------------------
    private suspend fun syncCasework(
        database: LifeOsDatabase,
        outboxDao: OutboxDao,
        prefs: SyncPrefs,
    ): Boolean {
        val leadDao = database.leadDao()

        val pending = outboxDao.getAll()
        val pushResults = pushPending("casework", pending, outboxDao) ?: return false

        val pendingCreateTexts = mutableListOf<String>()
        for ((row, _) in pushResults) {
            if (row.op == "create" && row.recordId != null && row.recordId < 0) {
                val text = JSONObject(row.payload).optString("text")
                if (text.isNotBlank()) pendingCreateTexts.add(text)
            }
        }

        // ---- pull ----
        val since = prefs.getCursor("casework")
        val pullResponse = SyncApi.pull(applicationContext, "casework", since)
        if (pullResponse == null) {
            Log.w(TAG, "[casework] Pull failed (network) -- will retry")
            return false
        }
        val rows = pullResponse.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val entry = rows.getJSONObject(i)
            val record = entry.getJSONObject("record")
            if (entry.optString("table") == "leads") {
                leadDao.upsert(record.toLeadEntity())
            }
        }
        val newCursor = pullResponse.optLong("new_cursor", since)
        if (newCursor != since) prefs.setCursor("casework", newCursor)

        // ---- reconcile locally-created temp rows against the real synced row ----
        // Matched by exact text (single-user, low-concurrency app -- see LeadDao doc comment).
        for (text in pendingCreateTexts) {
            val match = leadDao.findOpenMatchByText(text)
            if (match != null) {
                for (temp in leadDao.findTempRowsByText(text)) {
                    leadDao.deleteById(temp.id)
                }
            }
        }
        return true
    }
}

/** Maps a `leads` row JSON object (from GET /api/casework/pull) to a Room entity. */
private fun JSONObject.toLeadEntity(): LeadEntity = LeadEntity(
    id = getLong("id"),
    text = getString("text"),
    caseId = if (isNull("case_id")) null else optLong("case_id"),
    urgency = optString("urgency", "medium"),
    done = optInt("done", 0) != 0,
    createdAt = optString("created_at"),
    doneAt = if (isNull("done_at")) null else optString("done_at"),
    updatedAt = optString("updated_at"),
    updatedBy = optString("updated_by", "desktop"),
    deletedAt = if (isNull("deleted_at")) null else optString("deleted_at"),
    serverSeq = if (isNull("server_seq")) null else optLong("server_seq"),
)
