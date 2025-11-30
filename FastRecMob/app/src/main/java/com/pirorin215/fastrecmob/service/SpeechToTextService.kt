package com.pirorin215.fastrecmob.service

import com.google.api.gax.rpc.FixedHeaderProvider
import com.google.cloud.speech.v1.RecognitionAudio
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.RecognizeRequest
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.SpeechSettings
import com.google.protobuf.ByteString
import com.pirorin215.fastrecmob.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechToTextService(private val apiKey: String) {

    private var speechClient: SpeechClient? = null

    private fun getClient(): SpeechClient {
        if (speechClient == null) {
            if (apiKey.isEmpty()) {
                throw IllegalStateException("API key is not set. Please enter it in the settings.")
            }

            val headerProvider = FixedHeaderProvider.create("X-Goog-Api-Key", apiKey)

            val speechSettings = SpeechSettings.newBuilder()
                .setCredentialsProvider(com.google.api.gax.core.NoCredentialsProvider.create())
                .setHeaderProvider(headerProvider)
                .build()

            speechClient = SpeechClient.create(speechSettings)
        }
        return speechClient!!
    }

    private fun readWavHeader(file: File): Pair<Int, Short> {
        val fis = FileInputStream(file)
        try {
            val header = ByteArray(44)
            fis.read(header)

            val sampleRateBuffer = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN)
            val sampleRate = sampleRateBuffer.int

            val bitDepthBuffer = ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN)
            val bitDepth = bitDepthBuffer.short

            return Pair(sampleRate, bitDepth)
        } finally {
            fis.close()
        }
    }

    suspend fun transcribeFile(filePath: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext Result.failure(Exception("File not found: $filePath"))
                }

                val (sampleRate, bitDepth) = readWavHeader(file)

                val encoding = when (bitDepth.toInt()) {
                    8 -> RecognitionConfig.AudioEncoding.LINEAR16 // Note: API might not support 8-bit directly, often requires conversion. For now, we assume it's compatible or will be handled as 16-bit.
                    16 -> RecognitionConfig.AudioEncoding.LINEAR16
                    else -> return@withContext Result.failure(Exception("Unsupported bit depth: $bitDepth"))
                }

                val audioBytes = ByteString.copyFrom(file.readBytes())

                val config = RecognitionConfig.newBuilder()
                    .setEncoding(encoding)
                    .setSampleRateHertz(sampleRate)
                    .setLanguageCode("ja-JP")
                    .build()

                val audio = RecognitionAudio.newBuilder()
                    .setContent(audioBytes)
                    .build()

                val request = RecognizeRequest.newBuilder()
                    .setConfig(config)
                    .setAudio(audio)
                    .build()

                val response = getClient().recognize(request)

                val transcription = response.resultsList.joinToString("\n") { result ->
                    result.alternativesList.firstOrNull()?.transcript ?: ""
                }

                if (transcription.isNotBlank()) {
                    Result.success(transcription)
                } else {
                    Result.failure(Exception("Transcription result is empty."))
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    fun close() {
        speechClient?.shutdown()
        speechClient = null
    }
}
