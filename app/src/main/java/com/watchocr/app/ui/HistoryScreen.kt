package com.watchocr.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.watchocr.app.data.AppDatabase
import com.watchocr.app.data.OcrRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    // Remembered so recompositions keep collecting the same Flow instance —
    // collectAsStateWithLifecycle keys on it, and a fresh Flow per
    // recomposition would restart the Room query every time.
    val recordsFlow = remember { AppDatabase.getInstance(context).ocrRecordDao().getAll() }
    // null until Room's first emission; rendering nothing for that moment
    // (instead of the empty-state text) keeps "No OCR history yet" from
    // flashing on every open when history actually exists.
    val records = recordsFlow.collectAsStateWithLifecycle(initialValue = null).value ?: return
    val clipboardManager = LocalClipboardManager.current

    if (records.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No OCR history yet.\nTap + to pick an image, or set a watched folder in Settings.",
                modifier = Modifier.padding(32.dp),
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // Saveable so a configuration change (screen rotation) restores it along
    // with listState's scroll position: the top id is then unchanged, no
    // scroll happens, and the restored position survives.
    var lastTopId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Records are ordered newest-first; whenever a new record becomes the top
    // one (e.g. the background monitor added a result while the app was in
    // another app or backgrounded), jump the list back to the top so it's visible.
    LaunchedEffect(records) {
        val newTopId = records.firstOrNull()?.id
        if (newTopId != null && newTopId != lastTopId) {
            val isFirstLoad = lastTopId == null
            // Updated before scrolling: a user touch cancels the scroll
            // animation (and this effect), and the record must not count as
            // "new" again on the next emission.
            lastTopId = newTopId
            if (isFirstLoad) {
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(records, key = { it.id }) { record ->
            OcrRecordCard(
                record = record,
                onCopyOcrText = {
                    clipboardManager.setText(AnnotatedString(record.ocrText))
                    Toast.makeText(context, "Original text copied", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun OcrRecordCard(record: OcrRecord, onCopyOcrText: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                AsyncImage(
                    model = File(record.imagePath),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    dateFormatter.format(Date(record.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Original Text (tap to copy)", style = MaterialTheme.typography.labelLarge)
            Text(
                record.ocrText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCopyOcrText)
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(8.dp))
            Text("Translation", style = MaterialTheme.typography.labelLarge)
            Text(record.translation, style = MaterialTheme.typography.bodyMedium)

            if (record.analysis.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Idiom / Slang Analysis", style = MaterialTheme.typography.labelLarge)
                record.analysis.forEachIndexed { index, item ->
                    val line = if (item.expression.isBlank()) {
                        item.explanation
                    } else {
                        val furigana = item.furigana?.let { " ($it)" }.orEmpty()
                        "${item.expression}$furigana: ${item.explanation}"
                    }
                    Text("${index + 1}. $line", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
