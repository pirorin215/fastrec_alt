package com.pirorin215.fastrecmob.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.rpc.FixedHeaderProvider
import com.google.cloud.speech.v1.RecognitionAudio
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.RecognizeRequest
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.SpeechSettings
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class SpeechToTextService(private val context: Context, private val apiKey: String) {

    @Suppress("DEPRECATION")
    private fun getSignatureSha1Hex(): String? {
        try {
            val packageName = context.packageName
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNATURES.toLong()))
            } else {
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }

            val signatures = packageInfo.signatures
            if (signatures.isNullOrEmpty()) return null

            val signature = signatures[0]
            val md = MessageDigest.getInstance("SHA-1")
            md.update(signature.toByteArray())
            val digest = md.digest()

            // Convert byte array to lower-case hex string
            return digest.joinToString(separator = "") { "%02x".format(it) }

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun getClient(): SpeechClient {
        if (apiKey.isEmpty()) {
            throw IllegalStateException("API key is not set. Please enter it in the settings.")
        }

        val packageName = context.packageName
        val signatureHash = getSignatureSha1Hex() ?: throw IllegalStateException("Could not get signature hash.")

        val headers = mapOf(
            "X-Goog-Api-Key" to apiKey,
            "X-Android-Package" to packageName,
            "X-Android-Cert" to signatureHash
        )

        val headerProvider = FixedHeaderProvider.create(headers)

        val speechSettings = SpeechSettings.newBuilder()
            .setCredentialsProvider(NoCredentialsProvider.create())
            .setHeaderProvider(headerProvider)
            .build()

        return SpeechClient.create(speechSettings)
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

                var client: SpeechClient? = null
                try {
                    client = getClient()
                    val response = client.recognize(request)

                    val transcription = response.resultsList.joinToString("\n") { result ->
                        result.alternativesList.firstOrNull()?.transcript ?: ""
                    }

                    if (transcription.isNotBlank()) {
                        Result.success(transcription)
                    } else {
                        Result.failure(Exception("Transcription result is empty."))
                    }
                } finally {
                    client?.shutdown()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    suspend fun verifyApiKey(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("API key is empty."))
            }

            var speechClient: SpeechClient? = null
            try {
                speechClient = getClient()

                // ダミーの短い音声データ (16kHz, LINEAR16, モノラルの無音データ数バイト)
                val dummyAudioData = ByteArray(2) { 0 } // 1msの無音データ (16kHz * 16bit / 8bit/byte * 1ms = 2bytes)
                val audioBytes = ByteString.copyFrom(dummyAudioData)

                val config = RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                    .setSampleRateHertz(16000)
                    .setLanguageCode("en-US") // 言語は何でも良いが、エラーにならないように設定
                    .build()

                val audio = RecognitionAudio.newBuilder()
                    .setContent(audioBytes)
                    .build()

                val request = RecognizeRequest.newBuilder()
                    .setConfig(config)
                    .setAudio(audio)
                    .build()

                // recognizeを呼び出して認証を試みる
                speechClient.recognize(request)

                // 成功すればUnitを返す
                Result.success(Unit)
            } catch (e: Exception) {
                e.printStackTrace()
                when (e) {
                    is io.grpc.StatusRuntimeException -> {
                        if (e.status.code == io.grpc.Status.Code.UNAUTHENTICATED || e.status.code == io.grpc.Status.Code.PERMISSION_DENIED) {
                            Result.failure(Exception("API key authentication failed."))
                        } else {
                            Result.failure(e)
                        }
                    }
                    is com.google.api.gax.rpc.ApiException -> {
                        Result.failure(e)
                    }
                    is IllegalStateException -> {
                        // getClient()でAPIキーが空の場合にスローされる例外
                        Result.failure(e)
                    }
                    else -> Result.failure(e)
                }
            } finally {
                // クライアントをシャットダウンしてリソースを解放
                speechClient?.shutdown()
            }
        }
    }
}