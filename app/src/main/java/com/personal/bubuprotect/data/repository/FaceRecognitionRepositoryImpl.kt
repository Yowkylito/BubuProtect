package com.personal.bubuprotect.data.repository

import android.content.Context
import android.util.Log
import com.personal.bubuprotect.data.remote.LuxandApiService
import com.personal.bubuprotect.domain.model.RecognizedPerson
import com.personal.bubuprotect.domain.repository.FaceRecognitionRepository
import java.io.File

class FaceRecognitionRepositoryImpl(
    private val apiService: LuxandApiService,
    private val context: Context
) : FaceRecognitionRepository {

    override suspend fun recognizeFace(imageBytes: ByteArray): Result<RecognizedPerson?> {
        return try {
            // Save to a temporary file
            val tempFile = File(context.cacheDir, "temp_face_recognition.jpg")
            tempFile.writeBytes(imageBytes)
            
            // Read from file and send to API
            val results = apiService.recognizeFace(tempFile.readBytes())
            Log.d("YOWKEY","Results: $results")
            
            if (results.isNotEmpty()) {
                val firstResult = results.first()
                if (firstResult.probability > 0.9) {
                    Result.success(
                        RecognizedPerson(
                            id = firstResult.photoUuid,
                            name = "Recognized Person",
                            probability = firstResult.probability
                        )
                    )
                } else {
                    Result.success(null)
                }
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e("YOWKEY", "Recognition failed", e)
            Result.failure(e)
        }
    }

    override suspend fun enrollPerson(name: String, imageBytes: ByteArray): Result<String> {
        return try {
            // Save to a temporary file
            val tempFile = File(context.cacheDir, "temp_enrollment.jpg")
            tempFile.writeBytes(imageBytes)
            
            val response = apiService.enrollPerson(name, tempFile.readBytes())
            Log.d("YOWKEY","Enroll Response: $response")
            if (response.status == "success" && response.id != null) {
                Result.success(response.id)
            } else {
                Result.failure(Exception(response.message ?: "Enrollment failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
