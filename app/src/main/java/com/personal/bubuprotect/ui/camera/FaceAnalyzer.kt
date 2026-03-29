package com.personal.bubuprotect.ui.camera

import android.graphics.*
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream

class FaceAnalyzer(
    private val onFaceDetected: (ByteArray) -> Unit,
    private val onError: (Exception) -> Unit
) : ImageAnalysis.Analyzer {

    private var isProcessing = false
    private var isBlinking = false

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isProcessing) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val face = faces.first()
                        
                        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
                        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
                        
                        val eyesClosed = leftEyeOpenProb < 0.2f && rightEyeOpenProb < 0.2f
                        val eyesOpen = leftEyeOpenProb > 0.5f && rightEyeOpenProb > 0.5f

                        if (eyesClosed) {
                            isBlinking = true
                        } else if (isBlinking && eyesOpen && !isProcessing) {
                            isProcessing = true
                            isBlinking = false
                            
                            // 1. Convert ImageProxy to Bitmap using a reliable method
                            val bitmap = imageProxy.toBitmap()
                            
                            // 2. Rotate the bitmap to Portrait (since it's usually landscape from the sensor)
                            val portraitBitmap = rotateBitmap(bitmap, rotationDegrees.toFloat(), true)
                            
                            // 3. (Optional) Crop the face from the portrait bitmap if needed, 
                            // but let's first ensure the portrait bitmap itself is clean.
                            val stream = ByteArrayOutputStream()
                            portraitBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                            onFaceDetected(stream.toByteArray())
                        }
                    }
                }
                .addOnFailureListener { e ->
                    onError(e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float, mirror: Boolean): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        if (mirror) {
            // Mirroring is usually needed for the front camera
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // Reliable ImageProxy to Bitmap conversion to avoid green tint
    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    fun resume() {
        isProcessing = false
        isBlinking = false
    }
}
