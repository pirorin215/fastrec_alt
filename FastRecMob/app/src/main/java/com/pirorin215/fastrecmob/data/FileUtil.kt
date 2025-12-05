package com.pirorin215.fastrecmob.data

import android.os.Environment
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DateTimeInfo(val date: String, val time: String)

object FileUtil {
    fun getRecordingDateTimeInfo(fileName: String): DateTimeInfo {
        val regex = Regex("""[RM](\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})\.(wav|txt)""")
        val matchResult = regex.find(fileName)

        return if (matchResult != null && matchResult.groupValues.size > 1) {
            val dateTimeString = matchResult.groupValues[1] // "2025-12-01-02-04-08"
            try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                val date = inputFormat.parse(dateTimeString)

                val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                date?.let {
                    DateTimeInfo(dateFormat.format(it), timeFormat.format(it))
                } ?: DateTimeInfo("不明な日付", "不明な時刻")
            } catch (e: ParseException) {
                e.printStackTrace()
                DateTimeInfo("不明な日付", "不明な時刻")
            }
        } else {
            DateTimeInfo("不明な日付", "不明な時刻")
        }
    }

    // ファイル名から録音日時を抽出する
    // 例: R2025-12-01-02-04-08.wav -> 2025/12/01 02:04:08
    fun extractRecordingDateTime(fileName: String): String {
        val dateTimeInfo = getRecordingDateTimeInfo(fileName)
        return "${dateTimeInfo.date} ${dateTimeInfo.time}"
    }

    // タイムスタンプをファイル名に使用する形式にフォーマットする
    fun formatTimestampForFileName(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }

    fun formatTimestampToDateTimeString(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }

    fun getTimestampFromFileName(fileName: String): Long {
        val regex = Regex("""[RM](\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})\.(wav|txt)""")
        val matchResult = regex.find(fileName)

        return if (matchResult != null && matchResult.groupValues.size > 1) {
            val dateTimeString = matchResult.groupValues[1] // "2025-12-01-02-04-08"
            try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                inputFormat.parse(dateTimeString)?.time ?: 0L
            } catch (e: ParseException) {
                e.printStackTrace()
                0L
            }
        } else {
            0L
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