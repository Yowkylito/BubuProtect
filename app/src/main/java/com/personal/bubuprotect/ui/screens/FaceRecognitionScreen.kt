package com.personal.bubuprotect.ui.screens

import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.personal.bubuprotect.ui.camera.FaceAnalyzer
import com.personal.bubuprotect.ui.camera.FaceRecognitionViewModel
import com.personal.bubuprotect.ui.camera.RecognitionState
import org.koin.androidx.compose.koinViewModel
import java.util.concurrent.Executors

@Composable
fun FaceRecognitionScreen(
    modifier: Modifier = Modifier,
    onFaceRecognized: () -> Unit,
    onBack: () -> Unit,
    viewModel: FaceRecognitionViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.recognitionState.collectAsState()
    val capturedImage by viewModel.capturedImage.collectAsState()

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(state) {
        val currentState = state
        if (currentState is RecognitionState.Success) {
            Toast.makeText(context, currentState.message, Toast.LENGTH_LONG).show()
            onFaceRecognized()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                cameraExecutor, FaceAnalyzer(
                                    onFaceDetected = { bytes -> viewModel.onFaceDetected(bytes) },
                                    onError = { e -> viewModel.onError(e) }
                                ))
                        }

                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalyzer
                        )
                    } catch (e: Exception) {
                        viewModel.onError(e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
        /*        // Debug Overlay: Show the actual image being sent to the API
        capturedImage?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Sent to API:", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Last captured frame",
                        modifier = Modifier
                            .size(120.dp)
                            .border(2.dp, Color.Red)
                    )
                }
            }
        }*/

        // UI Overlays
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val s = state) {
                is RecognitionState.Loading -> {
                    CircularProgressIndicator()
                    Text("Processing face...", color = MaterialTheme.colorScheme.primary)
                }

                is RecognitionState.Error -> {
                    Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.reset() }) {
                        Text("Retry")
                    }
                }

                is RecognitionState.Success -> {
                    Text(s.message, color = MaterialTheme.colorScheme.primary)
                }

                else -> {
                    Text(
                        "Blink to capture and recognize",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onBack) {
                Text("Cancel")
            }
        }
    }
}
