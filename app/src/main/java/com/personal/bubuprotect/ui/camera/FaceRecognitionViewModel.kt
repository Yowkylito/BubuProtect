package com.personal.bubuprotect.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.domain.repository.FaceRecognitionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FaceRecognitionViewModel(
    val repository: FaceRecognitionRepository
) : ViewModel() {

    private val _recognitionState = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val recognitionState = _recognitionState.asStateFlow()

    private val _capturedImage = MutableStateFlow<ByteArray?>(null)
    val capturedImage = _capturedImage.asStateFlow()

    private var isProcessing = false

    fun onFaceDetected(imageBytes: ByteArray) {
        // Save the bytes to display them in the UI for debugging
        _capturedImage.value = imageBytes

        // Stop if already processing, or if an error/success is already displayed
        if (isProcessing || 
            _recognitionState.value is RecognitionState.Loading || 
            _recognitionState.value is RecognitionState.Success) return

        isProcessing = true
        _recognitionState.value = RecognitionState.Loading
        
        viewModelScope.launch {
            repository.recognizeFace(imageBytes)
                .onSuccess { person ->
                    if (person != null) {
                        _recognitionState.value = RecognitionState.Success("Welcome, ${person.name}!")
                    } else {
                        _recognitionState.value = RecognitionState.Error("Face not recognized")
                        isProcessing = false // Allow retry
                    }
                }
                .onFailure { e ->
                    _recognitionState.value = RecognitionState.Error(e.message ?: "Recognition failed")
                    isProcessing = false // Allow retry
                }
        }
    }

    fun onError(e: Exception) {
        if (!isProcessing && _recognitionState.value !is RecognitionState.Error) {
            _recognitionState.value = RecognitionState.Error(e.message ?: "Unknown error")
        }
    }
    
    fun reset() {
        isProcessing = false
        _recognitionState.value = RecognitionState.Idle
        _capturedImage.value = null
    }
}

sealed class RecognitionState {
    object Idle : RecognitionState()
    object Loading : RecognitionState()
    data class Success(val message: String) : RecognitionState()
    data class Error(val message: String) : RecognitionState()
}
