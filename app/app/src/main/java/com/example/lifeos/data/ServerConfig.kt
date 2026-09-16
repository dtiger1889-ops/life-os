package com.example.lifeos.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.lifeos.isEmulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.serverConfigStore by preferencesDataStore(name = "lifeos_server_config")
private val SERVER_URL_KEY = stringPreferencesKey("server_url")

/**
 * Persists the user-entered "Server URL" (the base URL of the PC running the Life OS sync
 * server, e.g. "http://YOUR-PC-HOSTNAME:8765") across launches. First-run gate: MainActivity
 * shows a setup screen instead of the card list whenever this is still [PLACEHOLDER_SERVER_URL]
 * -- the same value also reachable later from the card list's settings action.
 */
object ServerConfig {
    const val PLACEHOLDER_SERVER_URL = "http://YOUR-PC-HOSTNAME:8765"

    suspend fun getServerUrl(context: Context): String =
        context.serverConfigStore.data.map { it[SERVER_URL_KEY] ?: PLACEHOLDER_SERVER_URL }.first()

    fun observeServerUrl(context: Context): Flow<String> =
        context.serverConfigStore.data.map { it[SERVER_URL_KEY] ?: PLACEHOLDER_SERVER_URL }

    suspend fun setServerUrl(context: Context, url: String) {
        val normalized = url.trim().trimEnd('/')
        context.serverConfigStore.edit { it[SERVER_URL_KEY] = normalized }
    }

    /**
     * Resolves the effective base URL for network calls (manifest fetch + sync endpoints).
     * The AVD emulator's virtual network can't reach a real hostname/tailnet name, so if the
     * user hasn't configured anything yet (still the placeholder) AND we're running on a
     * virtual device, auto-try 10.0.2.2 -- the emulator's standard host-loopback alias -- on
     * the placeholder's own port. Once the user actually saves a URL (even on the emulator,
     * e.g. explicitly typing http://10.0.2.2:8770 for a non-default port), that value is used
     * unchanged -- this override only ever fires for the untouched placeholder.
     */
    fun resolveForUse(configuredUrl: String): String =
        if (configuredUrl == PLACEHOLDER_SERVER_URL && isEmulator()) "http://10.0.2.2:8765" else configuredUrl
}
