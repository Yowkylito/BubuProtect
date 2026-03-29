package com.personal.bubuprotect.data.remote

import android.util.Log
import com.personal.bubuprotect.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LuxandResult(
    @SerialName("photo_id") val photoId: Long,
    @SerialName("photo_uuid") val photoUuid: String,
    val probability: Double,
    val url: String
)

@Serializable
data class LuxandEnrollResponse(
    val status: String?=null,
    val id: String? = null,
    val message: String? = null
)

class LuxandApiService(private val client: HttpClient) {

    private val apiKey = BuildConfig.LUXAND_API_KEY

    suspend fun recognizeFace(imageBytes: ByteArray): List<LuxandResult> {
        return client.post("https://api.luxand.cloud/photo/search2") {
            header("token", apiKey)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("gallery", "43c3335b-472d-11ee-a9cf-0242ac130002")
                        append("all", "1")
                        append("photo", imageBytes, Headers.build {
                            append(HttpHeaders.ContentType, "image/jpeg")
                            append(HttpHeaders.ContentDisposition, "form-data; name=\"photo\"; filename=\"face.jpg\"")
                        })
                    }
                )
            )
        }.body()
    }

    suspend fun enrollPerson(name: String, imageBytes: ByteArray): LuxandEnrollResponse {
        return client.post("https://api.luxand.cloud/v2/person") {
            header("token", apiKey)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("name", name)
                        append("photos", imageBytes, Headers.build {
                            append(HttpHeaders.ContentType, "image/jpeg")
                            append(HttpHeaders.ContentDisposition, "form-data; name=\"photos\"; filename=\"enroll.jpg\"")
                        })
                        append("store", "1")
                        append("collections", "")
                        append("unique", "0")
                    }
                )
            )
        }.body()
    }

    companion object {
        fun create(): LuxandApiService {
            return LuxandApiService(
                HttpClient {
                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            coerceInputValues = true
                        })
                    }
                    install(Logging) {
                        logger = object : Logger {
                            override fun log(message: String) {
                                Log.d("LuxandApiService", message)
                            }
                        }
                        level = LogLevel.ALL
                    }
                }
            )
        }
    }
}
