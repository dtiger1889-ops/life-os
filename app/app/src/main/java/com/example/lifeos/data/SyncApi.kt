package com.example.lifeos.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin stdlib HTTP client for the starter kit's sync server (server/sync_server.py).
 * Plain HttpURLConnection (no retrofit/okhttp dependency in this app) -- GET /health,
 * GET /api/<db>/pull, POST /api/<db>/push. Every call returns null on any failure (timeout,
 * non-200, bad JSON); callers treat null as "retry later," never throw.
 */
object SyncApi {
    private const val TAG = "SyncApi"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000

    private const val QUICK_PROBE_TIMEOUT_MS = 1500

    suspend fun health(context: Context): JSONObject? = getJson("${baseUrl(context)}/health")

    /** Screen-entry reachability probe: a much shorter timeout than [health] so the two-tab
     * editable-dashboard screen's tab-default decision doesn't hang waiting on a dead server.
     * Same endpoint, same null-on-any-failure contract -- just tuned for "decide fast," not
     * "wait for a slow sync run." */
    suspend fun healthQuick(context: Context): JSONObject? =
        getJson(
            "${baseUrl(context)}/health",
            connectTimeoutMs = QUICK_PROBE_TIMEOUT_MS,
            readTimeoutMs = QUICK_PROBE_TIMEOUT_MS,
        )

    suspend fun pull(context: Context, dbName: String, since: Long): JSONObject? =
        getJson("${baseUrl(context)}/api/$dbName/pull?since=$since")

    suspend fun push(context: Context, dbName: String, mutations: JSONArray): JSONObject? {
        val body = JSONObject().put("mutations", mutations).toString()
        return postJson("${baseUrl(context)}/api/$dbName/push", body)
    }

    private suspend fun baseUrl(context: Context): String =
        ServerConfig.resolveForUse(ServerConfig.getServerUrl(context))

    private fun getJson(
        url: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GET $url -> HTTP ${connection.responseCode}")
                return null
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun postJson(url: String, body: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "POST $url -> HTTP ${connection.responseCode}")
                return null
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "POST $url failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
