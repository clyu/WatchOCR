package com.watchocr.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchocr.app.data.HistoryCleanup
import com.watchocr.app.data.SettingsDataStore
import com.watchocr.app.ocr.OcrProcessor
import com.watchocr.app.service.DirectoryMonitorService
import com.watchocr.app.ui.HistoryScreen
import com.watchocr.app.ui.SettingsScreen
import com.watchocr.app.ui.theme.WatchOcrTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchOcrTheme {
                WatchOcrApp()
            }
        }
    }
}

/** Top-level destinations, indexed by `selectedTab`; rendered as a bottom bar or a rail. */
private val navTabs = listOf(
    "History" to Icons.Default.History,
    "Settings" to Icons.Default.Settings
)

@Composable
fun WatchOcrApp(ocrViewModel: ManualOcrViewModel = viewModel()) {
    val context = LocalContext.current
    val settingsDataStore = remember { SettingsDataStore(context) }
    // null until DataStore's first emission; SettingsScreen is not composed
    // before then, so its text fields can seed directly from loaded values.
    val settings by settingsDataStore.settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    // Switching tabs disposes the outgoing one outright, so without somewhere to
    // park it its rememberSaveable state goes with it. Holds each tab's across
    // the switch; it is itself saveable, so a rotation keeps them too.
    val tabStateHolder = rememberSaveableStateHolder()
    // Manual imports and the directory-monitor service both run OCR through
    // OcrProcessor, which counts every in-flight job — the single source for
    // the FAB spinner.
    val ocrJobs by OcrProcessor.activeJobs.collectAsStateWithLifecycle()
    val isProcessing = ocrJobs > 0
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        ocrViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: notification is best-effort */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Keyed on canMonitor (does a key exist), not the key's text: revising an
    // already-set key changes nothing this effect decides, and start() is
    // idempotent but not free — no point invoking startForegroundService for
    // each revision. The monitor re-reads the key per file regardless. The path
    // stays a key so switching folders restarts the loop.
    //
    // Left nullable rather than flattened with `== true`, so "DataStore has not
    // emitted yet" is a key value of its own and the decision below can be read
    // straight off a key. Flattened to false it is indistinguishable from
    // settings that carry neither a folder nor a key: both keys read
    // (null, false) before and after that emission, so the effect would not run
    // for the loaded state at all and would instead be left holding whatever it
    // captured while settings was still null.
    val canMonitor = settings?.canMonitor
    // Re-run on every resume, not only when a key changes: the service stops
    // itself on conditions nothing here can predict (an unexpected failure, the
    // watched folder disappearing), and the alerts it leaves behind tell the
    // user to reopen WatchOCR. Reopening changes no key — the alert's intent
    // deliberately brings the existing activity forward rather than recreating
    // it (see DirectoryMonitorService.contentIntent), and a returning app
    // re-collects settings to the same value — so a plain LaunchedEffect keyed
    // on settings would only ever recover the cases that happened to destroy
    // the activity, and the instruction would be a dead end for the rest.
    //
    // Resume rather than start, so that tapping an alert from the notification
    // shade counts too (expanding it pauses without stopping), and so the
    // foreground-service start always happens from an unambiguously foreground
    // state.
    LifecycleResumeEffect(settings?.watchedDirPath, canMonitor) {
        when (canMonitor) {
            // Not loaded yet. Stopping on this would take down a monitor that
            // is already running — this effect runs again from scratch on every
            // resume and after every configuration change.
            null -> Unit
            true -> DirectoryMonitorService.start(context)
            // The service stops itself once it notices, but only when it next
            // reconciles or picks up a file — clearing the API key would
            // otherwise leave its "Watching…" notification up indefinitely.
            false -> DirectoryMonitorService.stop(context)
        }
        // Nothing to undo on the way out: monitoring is meant to outlive the UI,
        // and the one stop that does follow the app's lifecycle is the deliberate
        // one in DirectoryMonitorService.onTaskRemoved.
        onPauseOrDispose {}
    }

    LaunchedEffect(settings?.retentionDays) {
        val retentionDays = settings?.retentionDays ?: return@LaunchedEffect
        // Quietly: a failed sweep must not propagate out of the composition and
        // take the app down. The service's hourly pass retries.
        HistoryCleanup.deleteOlderThanQuietly(context, retentionDays)
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val current = settings
        if (uri == null || current == null) return@rememberLauncherForActivityResult
        if (current.apiKey.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("Please set your Gemini API key in Settings first.") }
            return@rememberLauncherForActivityResult
        }
        ocrViewModel.processImage(uri, current.apiKey, current.model)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!isLandscape) {
                NavigationBar {
                    navTabs.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                // Keyed on the tab, so leaving one saves its saveable state and
                // returning restores it — History's scroll position, and the
                // last-seen top record it decides auto-scrolls from. Without it
                // the branch below discards both, and every return to History
                // would read as a first load and jump to the top.
                tabStateHolder.SaveableStateProvider(selectedTab) {
                    if (selectedTab == 0) {
                        HistoryScreen()
                    } else {
                        settings?.let { SettingsScreen(settingsDataStore = settingsDataStore, settings = it) }
                    }
                }
                // After the tab content so the Box draws the manual-import FAB
                // over it; it belongs to the History tab alone. Outside the
                // provider because align() needs this Box's scope, and the FAB
                // has no state to carry across a switch anyway.
                if (selectedTab == 0) {
                    FloatingActionButton(
                        onClick = { pickImageLauncher.launch(arrayOf("image/*")) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                // The default is colorScheme.primary; the FAB's
                                // container is primaryContainer, and onPrimary is
                                // near-invisible on it in light themes.
                                // LocalContentColor is what the Icon branch uses.
                                color = LocalContentColor.current
                            )
                        } else {
                            Icon(Icons.Default.Add, contentDescription = "Add image")
                        }
                    }
                }
            }
            if (isLandscape) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    navTabs.forEachIndexed { index, (label, icon) ->
                        if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                        NavigationRailItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) }
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
