package com.example.lifeos.data

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStore by preferencesDataStore(name = "lifeos_sync_prefs")

/**
 * Persists each dashboard db's pull cursor (server_seq) across process death/restarts,
 * keyed generically by db name rather than one hardcoded property per db -- the reference
 * deployment this was forked from runs two independent dbs this way (each with its own
 * monotonic `sync_counter`), and keeping the same keyed shape here means adding a second
 * dashboard later is "call getCursor/setCursor with a new name," not a rewrite.
 */
class SyncPrefs(private val context: Context) {
    private fun cursorKey(db: String) = longPreferencesKey("${db}_cursor")

    suspend fun getCursor(db: String): Long =
        context.syncDataStore.data.map { it[cursorKey(db)] ?: 0L }.first()

    suspend fun setCursor(db: String, value: Long) {
        context.syncDataStore.edit { it[cursorKey(db)] = value }
    }
}
