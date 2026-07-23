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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

/**
 * The one loop over [navTabs], shared by the portrait bottom bar and the
 * landscape rail: [item] renders each tab with the container's own item
 * composable, receiving identical slots from here.
 */
@Composable
private fun NavTabItems(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    item: @Composable (
        index: Int,
        selected: Boolean,
        onClick: () -> Unit,
        icon: @Composable () -> Unit,
        label: @Composable () -> Unit
    ) -> Unit
) {
    navTabs.forEachIndexed { index, (label, icon) ->
        item(
            index,
            selectedTab == index,
            { onSelect(index) },
            { Icon(icon, contentDescription = null) },
            { Text(label) }
        )
    }
}

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

    // Keyed on canMonitor (does a key exist), not the key's text: every
    // keystroke in the API key field writes to DataStore, and start() is
    // idempotent but not free — no point invoking startForegroundService per
    // keystroke. bucketId stays a key so switching folders restarts the loop.
    val canMonitor = settings?.canMonitor == true
    LaunchedEffect(settings?.bucketId, canMonitor) {
        if (canMonitor) {
            DirectoryMonitorService.start(context)
        }
    }

    LaunchedEffect(settings?.retentionDays) {
        settings?.let { HistoryCleanup.deleteOlderThan(context, it.retentionDays) }
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
                    NavTabItems(selectedTab, { selectedTab = it }) { _, selected, onClick, icon, label ->
                        NavigationBarItem(selected = selected, onClick = onClick, icon = icon, label = label)
                    }
                }
            }
        }
    ) { padding ->
        Row(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    HistoryScreen()
                    // After HistoryScreen so the Box draws the manual-import FAB
                    // over it; it belongs to the History tab alone.
                    FloatingActionButton(
                        onClick = { pickImageLauncher.launch(arrayOf("image/*")) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Add, contentDescription = "Add image")
                        }
                    }
                } else {
                    settings?.let { SettingsScreen(settingsDataStore = settingsDataStore, settings = it) }
                }
            }
            if (isLandscape) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    NavTabItems(selectedTab, { selectedTab = it }) { index, selected, onClick, icon, label ->
                        if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                        NavigationRailItem(selected = selected, onClick = onClick, icon = icon, label = label)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
