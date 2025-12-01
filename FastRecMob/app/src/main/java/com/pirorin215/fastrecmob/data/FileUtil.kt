package com.pirorin215.fastrecmob.data

import android.os.Environment
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtil {
    // ファイル名から録音日時を抽出する
    // 例: R2025-12-01-02-04-08.wav -> 2025/12/01 02:04:08
    fun extractRecordingDateTime(fileName: String): String {
        val regex = Regex("""R(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})\.wav""")
        val matchResult = regex.find(fileName)

        return if (matchResult != null && matchResult.groupValues.size > 1) {
            val dateTimeString = matchResult.groupValues[1] // "2025-12-01-02-04-08"
            try {
                // Parse the string into a Date object
                val inputFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                val date = inputFormat.parse(dateTimeString)

                // Format the Date object into the desired output format
                val outputFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
                date?.let { outputFormat.format(it) } ?: "不明な日時"
            } catch (e: ParseException) {
                e.printStackTrace()
                "不明な日時"
            }
        } else {
            "不明な日時"
        }
    }

    // ファイル名からダウンロードフォルダ内のFileオブジェクトを取得する
    fun getAudioFile(context: android.content.Context, dirName: String, fileName: String): File {
        val audioDir = context.getExternalFilesDir(dirName)
        // audioDirが存在しない場合は作成する
        if (audioDir != null && !audioDir.exists()) {
            audioDir.mkdirs()
        }
        return File(audioDir, fileName)
    }
}