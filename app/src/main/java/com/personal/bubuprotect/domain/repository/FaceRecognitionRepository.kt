package com.personal.bubuprotect.domain.repository

import com.personal.bubuprotect.domain.model.RecognizedPerson

interface FaceRecognitionRepository {
    suspend fun recognizeFace(imageBytes: ByteArray): Result<RecognizedPerson?>
    suspend fun enrollPerson(name: String, imageBytes: ByteArray): Result<String>
}
