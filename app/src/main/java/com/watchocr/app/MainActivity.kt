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
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchocr.app.data.AppSettings
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

@Composable
fun WatchOcrApp(ocrViewModel: ManualOcrViewModel = viewModel()) {
    val context = LocalContext.current
    val settingsDataStore = remember { SettingsDataStore(context) }
    val settings by settingsDataStore.settingsFlow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val isManualProcessing by ocrViewModel.isProcessing.collectAsState()
    val ocrJobs by OcrProcessor.activeJobs.collectAsState()
    // Manual imports and the directory-monitor service both run OCR through
    // OcrProcessor; either should show the in-flight spinner on the FAB.
    val isProcessing = isManualProcessing || ocrJobs > 0
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        ocrViewModel.errors.collect { snackbarHostState.showSnackbar(it) }
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

    LaunchedEffect(settings.bucketId, settings.apiKey) {
        if (settings.bucketId != null && settings.apiKey.isNotBlank()) {
            DirectoryMonitorService.start(context)
        }
    }

    LaunchedEffect(settings.retentionDays) {
        HistoryCleanup.deleteOlderThan(context, settings.retentionDays)
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (settings.apiKey.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("Please set your Gemini API key in Settings first.") }
            return@rememberLauncherForActivityResult
        }
        ocrViewModel.processImage(uri, settings.apiKey, settings.model)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!isLandscape) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.History, contentDescription = null) },
                        label = { Text("History") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                }
            }
        }
    ) { padding ->
        Row(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> HistoryScreen()
                    else -> SettingsScreen(settingsDataStore = settingsDataStore, settings = settings)
                }
                if (selectedTab == 0) {
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
                }
            }
            if (isLandscape) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    Spacer(modifier = Modifier.weight(1f))
                    NavigationRailItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.History, contentDescription = null) },
                        label = { Text("History") }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NavigationRailItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
