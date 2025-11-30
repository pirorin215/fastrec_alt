package com.pirorin215.fastrecmob.data

data class DeviceSettings(
    val deepSleepDelayMs: String = "",
    val batVolMin: String = "",
    val batVolMult: String = "",
    val i2sSampleRate: String = "",
    val recMaxS: String = "",
    val recMinS: String = "",
    val audioGain: String = "",
    val vibraStartupMs: String = "",
    val vibraRecStartMs: String = "",
    val vibraRecStopMs: String = "",
    val vibra: Boolean = false,
    val logAtBoot: Boolean = false,
    val rtcDriftRatio: String = "",
    val hsHost: String = "",
    val hsPort: String = "",
    val hsPath: String = "",
    val hsUser: String = "",
    val hsPass: String = "",
    val wSsid0: String = "",
    val wPass0: String = "",
    val wSsid1: String = "",
    val wPass1: String = "",
    val wSsid2: String = "",
    val wPass2: String = "",
) {
    fun toIniString(): String {
        return """
            DEEP_SLEEP_DELAY_MS=$deepSleepDelayMs
            BAT_VOL_MIN=$batVolMin
            BAT_VOL_MULT=$batVolMult
            I2S_SAMPLE_RATE=$i2sSampleRate
            REC_MAX_S=$recMaxS
            REC_MIN_S=$recMinS
            AUDIO_GAIN=$audioGain
            VIBRA_STARTUP_MS=$vibraStartupMs
            VIBRA_REC_START_MS=$vibraRecStartMs
            VIBRA_REC_STOP_MS=$vibraRecStopMs
            VIBRA=$vibra
            LOG_AT_BOOT=$logAtBoot
            RTC_DRIFT_RATIO=$rtcDriftRatio
            HS_HOST=$hsHost
            HS_PORT=$hsPort
            HS_PATH=$hsPath
            HS_USER=$hsUser
            HS_PASS=$hsPass
            W_SSID_0=$wSsid0
            W_PASS_0=$wPass0
            W_SSID_1=$wSsid1
            W_PASS_1=$wPass1
            W_SSID_2=$wSsid2
            W_PASS_2=$wPass2
        """.trimIndent().lines().filter { it.contains("=") && it.split("=")[1].isNotEmpty() }.joinToString("\n")
    }

    fun diff(other: DeviceSettings): String {
        val differences = mutableListOf<String>()
        if (deepSleepDelayMs != other.deepSleepDelayMs) differences.add("DEEP_SLEEP_DELAY_MS: '${other.deepSleepDelayMs}' -> '$deepSleepDelayMs'")
        if (batVolMin != other.batVolMin) differences.add("BAT_VOL_MIN: '${other.batVolMin}' -> '$batVolMin'")
        if (batVolMult != other.batVolMult) differences.add("BAT_VOL_MULT: '${other.batVolMult}' -> '$batVolMult'")
        if (i2sSampleRate != other.i2sSampleRate) differences.add("I2S_SAMPLE_RATE: '${other.i2sSampleRate}' -> '$i2sSampleRate'")
        if (recMaxS != other.recMaxS) differences.add("REC_MAX_S: '${other.recMaxS}' -> '$recMaxS'")
        if (recMinS != other.recMinS) differences.add("REC_MIN_S: '${other.recMinS}' -> '$recMinS'")
        if (audioGain != other.audioGain) differences.add("AUDIO_GAIN: '${other.audioGain}' -> '$audioGain'")
        if (vibra != other.vibra) differences.add("VIBRA: '${other.vibra}' -> '$vibra'")
        if (logAtBoot != other.logAtBoot) differences.add("LOG_AT_BOOT: '${other.logAtBoot}' -> '$logAtBoot'")
        if (rtcDriftRatio != other.rtcDriftRatio) differences.add("RTC_DRIFT_RATIO: '${other.rtcDriftRatio}' -> '$rtcDriftRatio'")
        if (hsHost != other.hsHost) differences.add("HS_HOST: '${other.hsHost}' -> '$hsHost'")
        if (hsPort != other.hsPort) differences.add("HS_PORT: '${other.hsPort}' -> '$hsPort'")
        if (hsPath != other.hsPath) differences.add("HS_PATH: '${other.hsPath}' -> '$hsPath'")
        if (hsUser != other.hsUser) differences.add("HS_USER: '${other.hsUser}' -> '$hsUser'")
        if (hsPass != other.hsPass) differences.add("HS_PASS: '****' -> '****'")
        if (wSsid0 != other.wSsid0) differences.add("W_SSID_0: '${other.wSsid0}' -> '$wSsid0'")
        if (wPass0 != other.wPass0) differences.add("W_PASS_0: '****' -> '****'")
        if (wSsid1 != other.wSsid1) differences.add("W_SSID_1: '${other.wSsid1}' -> '$wSsid1'")
        if (wPass1 != other.wPass1) differences.add("W_PASS_1: '****' -> '****'")
        if (wSsid2 != other.wSsid2) differences.add("W_SSID_2: '${other.wSsid2}' -> '$wSsid2'")
        if (wPass2 != other.wPass2) differences.add("W_PASS_2: '****' -> '****'")
        return differences.joinToString("\n")
    }

    companion object {
        fun fromIniString(ini: String): DeviceSettings {
            val properties = ini.lines().mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()

            return DeviceSettings(
                deepSleepDelayMs = properties["DEEP_SLEEP_DELAY_MS"] ?: "",
                batVolMin = properties["BAT_VOL_MIN"] ?: "",
                batVolMult = properties["BAT_VOL_MULT"] ?: "",
                i2sSampleRate = properties["I2S_SAMPLE_RATE"] ?: "",
                recMaxS = properties["REC_MAX_S"] ?: "",
                recMinS = properties["REC_MIN_S"] ?: "",
                audioGain = properties["AUDIO_GAIN"] ?: "",
                vibraStartupMs = properties["VIBRA_STARTUP_MS"] ?: "",
                vibraRecStartMs = properties["VIBRA_REC_START_MS"] ?: "",
                vibraRecStopMs = properties["VIBRA_REC_STOP_MS"] ?: "",
                vibra = properties["VIBRA"]?.toBoolean() ?: false,
                logAtBoot = properties["LOG_AT_BOOT"]?.toBoolean() ?: false,
                rtcDriftRatio = properties["RTC_DRIFT_RATIO"] ?: "",
                hsHost = properties["HS_HOST"] ?: "",
                hsPort = properties["HS_PORT"] ?: "",
                hsPath = properties["HS_PATH"] ?: "",
                hsUser = properties["HS_USER"] ?: "",
                hsPass = properties["HS_PASS"] ?: "",
                wSsid0 = properties["W_SSID_0"] ?: "",
                wPass0 = properties["W_PASS_0"] ?: "",
                wSsid1 = properties["W_SSID_1"] ?: "",
                wPass1 = properties["W_PASS_1"] ?: "",
                wSsid2 = properties["W_SSID_2"] ?: "",
                wPass2 = properties["W_PASS_2"] ?: ""
            )
        }
    }
}