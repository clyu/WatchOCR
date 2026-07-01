package com.watchocr.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.watchocr.app.data.AppDatabase
import com.watchocr.app.data.AppSettings
import com.watchocr.app.data.SettingsDataStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settingsDataStore: SettingsDataStore, settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    val directoryLabel = remember(settings.directoryUri) { directoryDisplayName(context, settings.directoryUri) }

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val previousUri = settings.directoryUri
            scope.launch {
                if (previousUri != uri.toString()) {
                    AppDatabase.getInstance(context).monitoredFileDao().clear()
                }
                settingsDataStore.setDirectoryUri(uri.toString())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Monitored Directory", style = MaterialTheme.typography.titleMedium)
        Text(directoryLabel, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { directoryPickerLauncher.launch(null) }) {
            Text("Choose Directory")
        }

        Divider()

        Text("Gemini API Key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                scope.launch { settingsDataStore.setApiKey(it) }
            },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Divider()

        Text("Gemini Model (OCR)", style = MaterialTheme.typography.titleMedium)
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
}

private fun directoryDisplayName(context: Context, uriString: String?): String {
    if (uriString.isNullOrBlank()) return "No directory selected"
    return try {
        DocumentFile.fromTreeUri(context, Uri.parse(uriString))?.name ?: uriString
    } catch (e: Exception) {
        uriString
    }
}
