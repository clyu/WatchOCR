package com.watchocr.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchocr.app.ocr.OcrProcessor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Runs manual image imports in [viewModelScope] so an in-flight OCR request
 * survives configuration changes (e.g. screen rotation) instead of being
 * cancelled along with the composition's coroutine scope.
 */
class ManualOcrViewModel(application: Application) : AndroidViewModel(application) {

    /** Guards against a second import being started while one is in flight;
     * the UI's in-flight indicator reads [OcrProcessor.activeJobs] instead. */
    private var isProcessing = false

    /** User-facing snackbar messages: failures, and the busy notice below. */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun processImage(uri: Uri, apiKey: String, model: String) {
        if (isProcessing) {
            // The FAB stays tappable while processing (only manual imports are
            // serialized, not the monitor's jobs), so tell the user why the
            // picked image was not accepted instead of dropping it silently.
            _messages.trySend("An image is already being processed — please wait for it to finish.")
            return
        }
        isProcessing = true
        viewModelScope.launch {
            val result = OcrProcessor.processImage(getApplication(), uri, apiKey, model)
            isProcessing = false
            result.onFailure { _messages.send("OCR failed: ${OcrProcessor.describeFailure(it)}") }
        }
    }
}
