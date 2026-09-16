package com.example.lifeos

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lifeos.data.ServerConfig
import com.example.lifeos.data.SyncApi
import com.example.lifeos.data.SyncScheduler
import com.example.lifeos.ui.fields.ValidatedTextField
import com.example.lifeos.ui.leads.LeadsScreen
import com.example.lifeos.ui.leads.LeadsViewModel
import com.example.lifeos.ui.leads.LeadsViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Periodic is idempotent (KEEP) so re-registering on every launch is a no-op once
        // scheduled; the immediate run covers the "app foreground" trigger for a cold launch.
        SyncScheduler.schedulePeriodic(this)
        SyncScheduler.scheduleImmediate(this)
        setContent {
            LifeOsTheme {
                LifeOsApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // App-foreground trigger: covers relaunch-from-background too, not just cold launch.
        SyncScheduler.scheduleImmediate(this)
    }
}

@Composable
fun LifeOsApp() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Dashboard?>(null) }
    // Render immediately from the compiled-in fallback; the LaunchedEffect below fetches the
    // remote manifest off the main thread and swaps this in once it lands (or falls back to
    // the on-disk cache on failure -- see DashboardRegistry.kt).
    var dashboards by remember { mutableStateOf(FALLBACK_DASHBOARDS) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        serverUrl = ServerConfig.getServerUrl(context)
    }

    LaunchedEffect(serverUrl) {
        val url = serverUrl
        if (url != null && url != ServerConfig.PLACEHOLDER_SERVER_URL) {
            dashboards = loadDashboards(context)
        }
    }

    val url = serverUrl
    when {
        // DataStore hasn't answered yet -- brief, first frame only.
        url == null -> {}
        // First-run gate: never configured -- setup screen instead of the card list.
        url == ServerConfig.PLACEHOLDER_SERVER_URL && !showSettings -> {
            SetupScreen(
                currentUrl = url,
                onSave = { newUrl -> serverUrl = newUrl },
            )
        }
        // Reached later via the card list's settings action.
        showSettings -> {
            SetupScreen(
                currentUrl = url,
                onSave = { newUrl ->
                    serverUrl = newUrl
                    showSettings = false
                },
                onCancel = { showSettings = false },
            )
        }
        else -> {
            val current = selected
            if (current == null) {
                HomeScreen(
                    dashboards = dashboards,
                    onSelect = { selected = it },
                    onOpenSettings = { showSettings = true },
                )
            } else if (current.id == "casework") {
                // The reference dashboard gets the native two-tab edit surface. Gated by id.
                CaseworkScreen(dashboard = current, onBack = { selected = null })
            } else {
                BackHandler { selected = null }
                DashboardScreen(dashboard = current, onBack = { selected = null })
            }
        }
    }
}

/**
 * First-run (and settings-reachable) server-URL entry screen. The Server URL is the full
 * base URL of the PC running the Life OS sync server (server/sync_server.py) -- e.g.
 * "http://YOUR-PC-HOSTNAME:8765" -- persisted via ServerConfig (DataStore) and read by both
 * the manifest fetch and every sync endpoint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    currentUrl: String,
    onSave: (String) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var text by remember {
        mutableStateOf(if (currentUrl == ServerConfig.PLACEHOLDER_SERVER_URL) "" else currentUrl)
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text("Server setup") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Enter the base URL of the PC running the Life OS sync server, e.g. " +
                    "\"http://YOUR-PC-HOSTNAME:8765\". On the Android emulator, 10.0.2.2 " +
                    "reaches your host machine's localhost.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ValidatedTextField(
                value = text,
                onValueChange = { text = it },
                label = "Server URL",
                required = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                Button(
                    onClick = {
                        val url = text.trim().trimEnd('/')
                        if (url.isNotBlank()) {
                            scope.launch {
                                ServerConfig.setServerUrl(context, url)
                                onSave(url)
                            }
                        }
                    },
                ) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(dashboards: List<Dashboard>, onSelect: (Dashboard) -> Unit, onOpenSettings: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Life OS", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Server settings")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(dashboards) { dashboard ->
                DashboardCard(dashboard = dashboard, onClick = { onSelect(dashboard) })
            }
        }
    }
}

@Composable
fun DashboardCard(dashboard: Dashboard, onClick: () -> Unit) {
    val accentColor = Color(dashboard.accent)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Accent strip
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(72.dp)
                    .background(accentColor)
            )
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = dashboard.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dashboard.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(dashboard: Dashboard, onBack: () -> Unit) {
    var isError by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // Track if the main frame started loading (to distinguish main vs sub-resource errors)
    var loadStarted by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(dashboard.title) },
                actions = {
                    IconButton(onClick = {
                        isError = false
                        loadStarted = false
                        webViewRef?.reload()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Some dashboards gate a control button's POST on window.confirm()/
                        // alert(). Without a WebChromeClient, Android WebView silently returns
                        // false for confirm()/alert(), so those buttons appear but never fire.
                        // The base WebChromeClient supplies the default JS-dialog handling that
                        // makes confirm()/alert() actually work.
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            // Hand outbound links to the OS so they open in a real app
                            // (browser for web URLs, etc.) instead of loading inside this
                            // WebView. Same-host navigation -- the dashboard's own pages, its
                            // auto-refresh, and any control-button POSTs -- stays in the WebView.
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val target = request?.url ?: return false
                                val scheme = target.scheme?.lowercase()
                                if (scheme != "http" && scheme != "https") return false
                                val currentHost = view?.url?.let { Uri.parse(it).host }
                                if (target.host != null && target.host == currentHost) {
                                    return false
                                }
                                return try {
                                    val intent = Intent(Intent.ACTION_VIEW, target)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    view?.context?.startActivity(intent)
                                    true
                                } catch (e: ActivityNotFoundException) {
                                    false // No app to handle it -- let the WebView try.
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                loadStarted = true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                // Only show the error card for the main frame
                                if (request?.isForMainFrame == true) {
                                    isError = true
                                }
                            }
                        }
                        loadUrl(dashboard.url)
                        webViewRef = this
                    }
                },
                update = { webView ->
                    webViewRef = webView
                }
            )

            // Offline error card overlaid on top of the WebView
            if (isError) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .padding(32.dp)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "${dashboard.title} is offline",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Server asleep or not running",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = {
                                isError = false
                                loadStarted = false
                                webViewRef?.reload()
                            }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Two-tab casework surface: "Dashboard" tab is the existing WebView (untouched, verbatim
 * behavior); "Edit" tab is the native Leads screen. Only reached when dashboard.id ==
 * "casework" (see LifeOsApp above).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseworkScreen(dashboard: Dashboard, onBack: () -> Unit) {
    var tabIndex by remember { mutableStateOf(0) }
    var userSwitchedTab by remember { mutableStateOf(false) }
    var pcUnreachable by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val leadsViewModel: LeadsViewModel = viewModel(factory = LeadsViewModelFactory(context))

    // Screen-entry reachability probe: a quick GET /health off the main thread. Dashboard tab
    // (WebView) is useless with the server unreachable, but Edit works fully offline from Room --
    // so an unreachable server auto-switches to Edit, UNLESS the user has already tapped a tab
    // themselves (a manual tap always wins over the auto-switch).
    LaunchedEffect(Unit) {
        val reachable = withContext(Dispatchers.IO) { SyncApi.healthQuick(context) != null }
        pcUnreachable = !reachable
        if (!reachable && !userSwitchedTab) {
            tabIndex = 1
        }
    }

    // Back handling, most-specific first: close whichever overlay (edit sheet / quick-add) is
    // frontmost, then finally leave the screen back to the card list.
    val anyOverlayOpen = leadsViewModel.editingLead != null || leadsViewModel.showQuickAdd

    BackHandler(enabled = anyOverlayOpen) {
        when {
            leadsViewModel.editingLead != null -> leadsViewModel.closeEdit()
            leadsViewModel.showQuickAdd -> leadsViewModel.cancelQuickAdd()
        }
    }
    BackHandler(enabled = !anyOverlayOpen) { onBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(dashboard.title) },
                    actions = {
                        if (tabIndex == 0) {
                            IconButton(onClick = {
                                isError = false
                                webViewRef?.reload()
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload")
                            }
                        }
                    }
                )
                TabRow(selectedTabIndex = tabIndex) {
                    Tab(
                        selected = tabIndex == 0,
                        onClick = { userSwitchedTab = true; tabIndex = 0 },
                        text = { Text("Dashboard") },
                    )
                    Tab(
                        selected = tabIndex == 1,
                        onClick = { userSwitchedTab = true; tabIndex = 1 },
                        text = { Text("Edit") },
                    )
                }
            }
        }
    ) { padding ->
        // Full PaddingValues (top app bar + tab row + bottom nav bar/gesture inset) -- the
        // Edit tab's FAB and bottom sheet need the bottom inset too, unlike the plain
        // WebView-only DashboardScreen above which only needed the top inset.
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tabIndex) {
                0 -> EditableDashboardWebViewPane(
                    dashboard = dashboard,
                    isError = isError,
                    onErrorChange = { isError = it },
                    onWebViewReady = { webViewRef = it },
                )
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    if (pcUnreachable) {
                        PcUnreachableBanner()
                    }
                    LeadsScreen(viewModel = leadsViewModel, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Thin persistent banner shown atop the Edit tab's content when the screen-entry reachability
 * probe (SyncApi.healthQuick(), fired once per CaseworkScreen entrance) found the server
 * unreachable. Edits still work fully offline from Room -- this just sets expectations that
 * nothing has synced yet. Stateless; no dismiss control (per design, it just stops rendering
 * once a later screen entry probes successfully). Screenshot-tested in LifeosScreenshots.kt. */
@Composable
fun PcUnreachableBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = "PC unreachable — showing local data. Edits will sync when it's back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** The WebView + offline-error-overlay half of the two-tab casework surface's Dashboard tab.
 * State is hoisted to the caller so the top bar's reload action can drive the same WebView
 * instance. */
@Composable
private fun EditableDashboardWebViewPane(
    dashboard: Dashboard,
    isError: Boolean,
    onErrorChange: (Boolean) -> Unit,
    onWebViewReady: (WebView) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url ?: return false
                            val scheme = target.scheme?.lowercase()
                            if (scheme != "http" && scheme != "https") return false
                            val currentHost = view?.url?.let { Uri.parse(it).host }
                            if (target.host != null && target.host == currentHost) {
                                return false
                            }
                            return try {
                                val intent = Intent(Intent.ACTION_VIEW, target)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                view?.context?.startActivity(intent)
                                true
                            } catch (e: ActivityNotFoundException) {
                                false
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                onErrorChange(true)
                            }
                        }
                    }
                    loadUrl(dashboard.url)
                    onWebViewReady(this)
                }
            },
            update = { webView -> onWebViewReady(webView) }
        )

        if (isError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Card(modifier = Modifier.padding(32.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "${dashboard.title} is offline",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Server asleep or not running",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
