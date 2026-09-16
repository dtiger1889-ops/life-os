package com.example.lifeos

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.lifeos.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "DashboardRegistry"
private const val CACHE_FILENAME = "dashboards_cache.json"
private const val CONNECT_TIMEOUT_MS = 3000
private const val READ_TIMEOUT_MS = 3000

// The AVD emulator's virtual network doesn't resolve a real hostname/tailnet name -- see
// ServerConfig.resolveForUse(), which routes the manifest fetch + all sync endpoints through
// 10.0.2.2 (the emulator's standard host-loopback alias) when running on a virtual device
// and the server URL is still unconfigured.
fun isEmulator(): Boolean =
    Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.PRODUCT.startsWith("sdk_gphone")

private fun manifestUrl(baseUrl: String): String = "$baseUrl/dashboards.json"

// kind is forward wiring: it does not change behavior yet, every card still opens its WebView
// as today. `id` gates the two-tab native-edit surface (MainActivity.LifeOsApp) -- only the
// dashboard whose id == "casework" gets it; anything else stays WebView-only.
data class Dashboard(
    val title: String,
    val subtitle: String,
    val url: String,
    val accent: Long,
    val kind: String = "viewonly",
    val id: String = ""
)

// Compiled-in last resort -- used only when both the network fetch AND the on-disk cache are
// unavailable (e.g. a fresh install launched offline before ever reaching the server once).
// Mirrors server/dashboards.json's one reference entry.
val FALLBACK_DASHBOARDS = listOf(
    Dashboard(
        "Casework", "the game is afoot",
        "${ServerConfig.PLACEHOLDER_SERVER_URL}/casework/dashboard.html",
        0xFF8FB96A, "editable", "casework",
    ),
)

/**
 * Fetches the remote dashboard manifest (`dashboards.json`, served statically off the same
 * server that serves the dashboards themselves), falling back to the last-known-good on-disk
 * cache, then the compiled-in list, in that order. Never throws -- always returns a usable
 * list. Does its own blocking I/O internally on Dispatchers.IO; safe to call from a Composable
 * coroutine (e.g. LaunchedEffect) without blocking the UI thread.
 */
suspend fun loadDashboards(context: Context): List<Dashboard> {
    val cacheFile = File(context.filesDir, CACHE_FILENAME)
    val baseUrl = ServerConfig.resolveForUse(ServerConfig.getServerUrl(context))

    val fetched = withContext(Dispatchers.IO) { fetchManifest(baseUrl) }
    if (fetched != null) {
        val parsed = parseManifest(fetched)
        if (!parsed.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching { writeCacheAtomically(cacheFile, fetched) }
                    .onFailure { Log.w(TAG, "Failed to write dashboard cache", it) }
            }
            return parsed
        }
    }

    // Fetch failed, or returned something we couldn't parse into any entries -- fall back to
    // the last-known-good cache written by a previous successful fetch.
    val cached = withContext(Dispatchers.IO) {
        runCatching { if (cacheFile.exists()) cacheFile.readText() else null }.getOrNull()
    }
    if (cached != null) {
        val parsedCache = parseManifest(cached)
        if (!parsedCache.isNullOrEmpty()) {
            return parsedCache
        }
    }

    // No network, no usable cache -- compiled-in list.
    return FALLBACK_DASHBOARDS
}

private fun fetchManifest(baseUrl: String): String? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(manifestUrl(baseUrl)).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            Log.w(TAG, "Manifest fetch HTTP ${connection.responseCode}")
            return null
        }
        connection.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "Manifest fetch failed", e)
        null
    } finally {
        connection?.disconnect()
    }
}

// Write-to-temp-then-rename so a crash or kill mid-write never leaves a half-written (and thus
// unparseable) cache file behind -- the rename is atomic on the same filesystem.
private fun writeCacheAtomically(cacheFile: File, contents: String) {
    val tmp = File(cacheFile.parentFile, "$CACHE_FILENAME.tmp")
    tmp.writeText(contents)
    if (!tmp.renameTo(cacheFile)) {
        cacheFile.writeText(contents)
        tmp.delete()
    }
}

private data class ManifestEntry(val group: String, val order: Int, val dashboard: Dashboard)

/**
 * Parses a dashboards.json payload. Returns null when the payload isn't valid JSON or doesn't
 * have the expected top-level `dashboards` array at all -- that signals the caller to fall back
 * to the previous source instead. Individual malformed entries within a valid array are skipped
 * rather than failing the whole parse, so one bad card doesn't take down the other six.
 */
private fun parseManifest(json: String): List<Dashboard>? {
    return try {
        val root = JSONObject(json)
        val array = root.optJSONArray("dashboards") ?: return null
        val entries = mutableListOf<ManifestEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val dashboard = parseEntry(obj) ?: continue
            entries.add(ManifestEntry(obj.optString("group", ""), obj.optInt("order", 999), dashboard))
        }
        entries.sortedWith(compareBy({ it.group }, { it.order })).map { it.dashboard }
    } catch (e: Exception) {
        Log.w(TAG, "Manifest parse failed", e)
        null
    }
}

// label + url are the only required fields; everything else has a documented default so a
// mid-edit / older-schema manifest degrades gracefully instead of dropping the whole card.
private fun parseEntry(obj: JSONObject): Dashboard? {
    val label = obj.optString("label").takeIf { it.isNotBlank() } ?: return null
    val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
    val subtitle = obj.optString("subtitle", "")
    val kind = obj.optString("kind", "viewonly")
    val id = obj.optString("id", "")
    val accent = parseColorLong(obj.optString("color", ""))
    return Dashboard(title = label, subtitle = subtitle, url = url, accent = accent, kind = kind, id = id)
}

// Manifest colors are authored as "0xFFRRGGBB" strings; on any parse failure fall back to a
// neutral gray rather than dropping the entry over a cosmetic field.
private fun parseColorLong(raw: String): Long {
    if (raw.isBlank()) return 0xFF888888
    return try {
        raw.removePrefix("0x").removePrefix("0X").toLong(16)
    } catch (e: NumberFormatException) {
        0xFF888888
    }
}
