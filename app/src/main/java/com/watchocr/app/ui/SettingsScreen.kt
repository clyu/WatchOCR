package com.watchocr.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.watchocr.app.data.AppSettings
import com.watchocr.app.data.HistoryCleanup
import com.watchocr.app.data.ImageBucket
import com.watchocr.app.data.MediaStoreImages
import com.watchocr.app.data.SettingsDataStore
import com.watchocr.app.service.DirectoryMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Permission granting access to all images on this OS version. */
private val mediaImagesPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Permissions requested together when access is missing. Watching a folder
 * needs access to every image that lands in it, so partial ("selected
 * photos") access is not enough — but READ_MEDIA_VISUAL_USER_SELECTED is
 * still requested alongside on Android 14+: for a user who previously chose
 * partial access, re-requesting then shows the upgrade dialog with an
 * "Allow all" option instead of being flatly denied.
 */
private val mediaImagesRequest: Array<String>
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(mediaImagesPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else {
        arrayOf(mediaImagesPermission)
    }

/** Shown for "keep forever" (0) and for any persisted value outside the offered choices. */
private const val RETENTION_NEVER_LABEL = "Never"

/** Auto-delete choices: days to keep OCR results -> menu label, 0 = keep forever. */
private val retentionLabels = mapOf(
    0 to RETENTION_NEVER_LABEL,
    1 to "After 1 day",
    7 to "After 7 days",
    30 to "After 30 days"
)

/**
 * One titled block of the settings list. Emits its children straight into the
 * caller's [Column] instead of nesting a layout of its own, so the screen's
 * `spacedBy(16.dp)` keeps applying between the title and every element of
 * [content] exactly as it did when these were written out inline.
 *
 * The separator belongs to the section that follows it, so only the first
 * section opts out of one.
 */
@Composable
private fun SettingsSection(
    title: String,
    dividerAbove: Boolean = true,
    content: @Composable () -> Unit
) {
    if (dividerAbove) HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
    content()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsDataStore: SettingsDataStore, settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The text fields own their state, seeded exactly once from [settings],
    // which the caller guarantees is already loaded (this screen is not
    // composed before DataStore's first emission). Keying remember on the
    // round-tripped settings value would let a stale DataStore emission reset
    // the field mid-typing and drop keystrokes.
    // remember, not rememberSaveable: the key must not land in the saved
    // instance state Bundle in plain text. Re-seeding from [settings] after a
    // configuration change is equivalent anyway, since every edit is persisted.
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    // Non-null while the folder picker dialog is showing.
    var pickerBuckets by remember { mutableStateOf<List<ImageBucket>?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var retentionMenuExpanded by remember { mutableStateOf(false) }

    fun openFolderPicker() {
        scope.launch {
            pickerBuckets = withContext(Dispatchers.IO) { MediaStoreImages.queryBuckets(context) }
        }
    }

    // Most messages here explain a permission or path problem the user has to
    // read and act on, so LENGTH_LONG is the default.
    fun toast(message: String, duration: Int = Toast.LENGTH_LONG) {
        Toast.makeText(context, message, duration).show()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val partialAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            results[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        when {
            results[mediaImagesPermission] == true -> openFolderPicker()
            partialAccess -> toast(
                "Watching a folder needs access to all photos — tap Choose Folder again and allow access to all photos."
            )
            else -> toast("Photo access is required to choose a folder.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSection("Monitored Folder", dividerAbove = false) {
            Text(settings.bucketName ?: "No folder selected", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                val hasFullAccess = ContextCompat.checkSelfPermission(context, mediaImagesPermission) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasFullAccess) openFolderPicker() else permissionLauncher.launch(mediaImagesRequest)
            }) {
                Text("Choose Folder")
            }
        }

        SettingsSection("Gemini API Key") {
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    scope.launch { settingsDataStore.setApiKey(it) }
                },
                label = { Text("API Key") },
                singleLine = true,
                // Password keyboard: keeps the IME from learning the key and
                // offering it back as a suggestion in other apps.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation =
                    if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            imageVector =
                                if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingsSection("Gemini Model (OCR)") {
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    scope.launch { settingsDataStore.setModel(it) }
                },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingsSection("Auto-delete History") {
            ExposedDropdownMenuBox(
                expanded = retentionMenuExpanded,
                onExpandedChange = { retentionMenuExpanded = it }
            ) {
                OutlinedTextField(
                    // Any persisted value outside the offered choices reads as "Never",
                    // matching how HistoryCleanup treats anything <= 0.
                    value = retentionLabels[settings.retentionDays] ?: RETENTION_NEVER_LABEL,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("Delete results") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = retentionMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = retentionMenuExpanded,
                    onDismissRequest = { retentionMenuExpanded = false }
                ) {
                    retentionLabels.forEach { (days, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                retentionMenuExpanded = false
                                // MainActivity's LaunchedEffect(settings.retentionDays)
                                // runs the cleanup once this write lands.
                                scope.launch { settingsDataStore.setRetentionDays(days) }
                            }
                        )
                    }
                }
            }
            Button(onClick = { showClearConfirm = true }) {
                Text("Clear History Now")
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all history?") },
            text = { Text("All OCR results and their saved images will be deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        HistoryCleanup.clearAll(context)
                        toast("History cleared", Toast.LENGTH_SHORT)
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    pickerBuckets?.let { buckets ->
        AlertDialog(
            onDismissRequest = { pickerBuckets = null },
            title = { Text("Choose a folder") },
            text = {
                if (buckets.isEmpty()) {
                    Text("No image folders found on this device.")
                } else {
                    LazyColumn {
                        items(buckets, key = { it.id }) { bucket ->
                            Text(
                                "${bucket.name} (${bucket.imageCount})",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            val dirPath = withContext(Dispatchers.IO) {
                                                MediaStoreImages.queryBucketPath(context, bucket.id)
                                            }
                                            if (dirPath == null) {
                                                toast("Couldn't determine this folder's path; choose another folder.")
                                            } else {
                                                settingsDataStore.setWatchedBucket(bucket.id, bucket.name, dirPath)
                                                // Revives a self-stopped service even when the
                                                // re-selected folder is the one it was already
                                                // configured for — MainActivity's LaunchedEffect
                                                // key doesn't change then. start() is idempotent,
                                                // so a running service is unaffected.
                                                if (settingsDataStore.settingsFlow.first().canMonitor) {
                                                    DirectoryMonitorService.start(context)
                                                }
                                            }
                                            pickerBuckets = null
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerBuckets = null }) { Text("Cancel") }
            }
        )
    }
}
