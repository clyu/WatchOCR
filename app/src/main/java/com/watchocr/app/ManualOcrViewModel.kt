package com.watchocr.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchocr.app.ocr.OcrProcessor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Runs manual image imports in [viewModelScope] so an in-flight OCR request
 * survives configuration changes (e.g. screen rotation) instead of being
 * cancelled along with the composition's coroutine scope.
 */
class ManualOcrViewModel(application: Application) : AndroidViewModel(application) {

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    fun processImage(uri: Uri, apiKey: String, model: String) {
        if (_isProcessing.value) return
        _isProcessing.value = true
        viewModelScope.launch {
            val result = OcrProcessor.processImage(getApplication(), uri, apiKey, model)
            _isProcessing.value = false
            result.onFailure {
                val reason = it.message.orEmpty().ifBlank { it.javaClass.simpleName }.take(200)
                _errors.send("OCR failed: $reason")
            }
        }
    }
}
