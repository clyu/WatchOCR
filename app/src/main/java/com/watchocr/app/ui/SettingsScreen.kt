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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchocr.app.data.AppSettings
import com.watchocr.app.data.HistoryCleanup
import com.watchocr.app.data.ImageBucket
import com.watchocr.app.data.MediaStoreImages
import com.watchocr.app.data.SettingsDataStore
import com.watchocr.app.service.DirectoryMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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

/**
 * Edits to a settings text field are coalesced over this window before being
 * persisted. Long enough to swallow a burst of keystrokes, short enough that the
 * flush on the way out of the screen is the exception rather than the rule.
 */
private const val SETTINGS_WRITE_DEBOUNCE_MS = 400L

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

/**
 * Persists edits to one settings text field through [write], coalesced over
 * [SETTINGS_WRITE_DEBOUNCE_MS] rather than issued per keystroke: every DataStore
 * edit is a read-modify-write and an fsync of the whole preferences file, so
 * typing a value out by hand would otherwise mean one of those per character.
 * Pasting — how most API keys arrive — is a single change either way.
 *
 * The write carrying the last edit can still be owed when the screen leaves
 * composition (a tab switch, a rotation), and it has to happen anyway: the
 * draft in SettingsDraftViewModel outlives the composition but not the process,
 * so an edit that never reached DataStore is gone the moment the app is. That
 * is the guarantee SettingsDataStore's NonCancellable writes exist for, and the
 * flush below is what keeps a debounce from weakening it.
 *
 * The flush cannot run on the caller's rememberCoroutineScope, which is
 * cancelled at precisely the moment it is needed — a launch into a cancelled
 * scope never runs — so it gets one of its own that is deliberately never
 * cancelled. All that scope ever holds is a single-key DataStore write, which is
 * NonCancellable and completes on its own.
 *
 * [stored] is what DataStore already holds, so leaving a field nobody touched
 * writes nothing.
 */
@Composable
private fun PersistDebounced(value: String, stored: String, write: suspend (String) -> Unit) {
    // rememberUpdatedState throughout: the two effects below are keyed on Unit so
    // that a recomposition does not restart the debounce mid-edit, which leaves
    // them holding the arguments of the composition that created them.
    val currentValue = rememberUpdatedState(value)
    val currentStored = rememberUpdatedState(stored)
    val currentWrite = rememberUpdatedState(write)

    LaunchedEffect(Unit) {
        snapshotFlow { currentValue.value }
            // The value the field opens with is never an edit to persist: it is
            // either what DataStore already holds, or a draft whose write the
            // departing composition's flush already owns.
            .drop(1)
            .collectLatest { edited ->
                delay(SETTINGS_WRITE_DEBOUNCE_MS)
                currentWrite.value(edited)
            }
    }

    val flushScope = remember { CoroutineScope(Dispatchers.Main.immediate) }
    DisposableEffect(Unit) {
        onDispose {
            val edited = currentValue.value
            // Main.immediate, so this starts on the spot instead of waiting for a
            // dispatch that the departing screen may not be around for.
            if (edited != currentStored.value) flushScope.launch { currentWrite.value(edited) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsDataStore: SettingsDataStore,
    settings: AppSettings,
    draft: SettingsDraftViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Each field shows the user's own edit once there is one, and the stored
    // value until then. The edits live in [draft] rather than in composition
    // state precisely so that leaving and re-entering this screen never re-reads
    // them from DataStore — [SettingsDraftViewModel] spells out what that race
    // costs. [settings] is already loaded, the caller guarantees it.
    val apiKey = draft.apiKey ?: settings.apiKey
    val model = draft.model ?: settings.model
    var apiKeyVisible by remember { mutableStateOf(false) }

    // Non-null while the folder picker dialog is showing.
    var pickerBuckets by remember { mutableStateOf<List<ImageBucket>?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var retentionMenuExpanded by remember { mutableStateOf(false) }

    // Neither text field writes from its onValueChange; both go through this,
    // which is also what makes the last edit land after the field is gone.
    PersistDebounced(value = apiKey, stored = settings.apiKey) { settingsDataStore.setApiKey(it) }
    PersistDebounced(value = model, stored = settings.model) { settingsDataStore.setModel(it) }

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
                // Persisted by the debounced writer above, not from here.
                value = apiKey,
                onValueChange = { draft.apiKey = it },
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
                onValueChange = { draft.model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    // A blank model is stored as "unset" and resolves to
                    // AppSettings.DEFAULT_MODEL, so an emptied field would show
                    // nothing while requests kept using that default. Filling it
                    // back in on blur — not on every keystroke, which would make
                    // the field impossible to clear and retype — keeps what is
                    // displayed equal to what is sent. Persisting it is the
                    // debounced writer's job, same as any other edit.
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && model.isBlank()) {
                            draft.model = AppSettings.DEFAULT_MODEL
                        }
                    }
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
                                            settingsDataStore.setWatchedBucket(bucket.name, bucket.path)
                                            // Revives a self-stopped service even when the
                                            // re-selected folder is the one it was already
                                            // configured for — MainActivity's LaunchedEffect
                                            // key doesn't change then. start() is idempotent,
                                            // so a running service is unaffected.
                                            if (settingsDataStore.settingsFlow.first().canMonitor) {
                                                DirectoryMonitorService.start(context)
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
