#include "fastrec_alt.h"

#include <time.h>
#include <sys/time.h>
#include <Base64.h>
#include "driver/rtc_io.h"
#include "esp_bt.h"
#include "esp_wifi.h"
#include "esp_sleep.h"
#include <Wire.h>

#define BUTTON_PIN_BITMASK(GPIO) (1ULL << GPIO)

// --- Function Implementations ---
void serialWait() {
  unsigned long startTime = millis();
  while (!Serial && (millis() - startTime < SERIAL_TIMEOUT_MS)) {
    delay(10);
  }
  applog("Serial init.");
}

// Define valid state transitions
const AppState validTransitions[][2] = {
    {INIT, IDLE},
    {INIT, REC},
    {INIT, UPLOAD},
    {INIT, SETUP},
    {IDLE, REC},
    {REC, IDLE},
    {IDLE, UPLOAD},
    {UPLOAD, IDLE},
    {IDLE, DSLEEP},
    {SETUP, DSLEEP}
};
const size_t NUM_VALID_TRANSITIONS = sizeof(validTransitions) / sizeof(validTransitions[0]);

void setAppState(AppState newState, bool applyDebounce = true) {
  static unsigned long lastStateChangeTime = 0; // 状態変更のデバウンス用

  if (g_currentAppState != newState) {
    // デバウンス時間内に連続して状態変更が要求された場合は無視する
    if (applyDebounce && (millis() - lastStateChangeTime < STATE_CHANGE_DEBOUNCE_MS)) {
      applog("Ignoring rapid state change request to %s (current: %s)", appStateStrings[newState], appStateStrings[g_currentAppState]);
      return;
    }

    // Check if the requested transition is valid
    bool isValidTransition = false;
    for (size_t i = 0; i < NUM_VALID_TRANSITIONS; ++i) {
      if (validTransitions[i][0] == g_currentAppState && validTransitions[i][1] == newState) {
        isValidTransition = true;
        break;
      }
    }

    if (!isValidTransition) {
      applog("ERROR: Attempted invalid state transition from %s to %s. Ignoring request.",
                    appStateStrings[g_currentAppState], appStateStrings[newState]);
      return; // Ignore invalid transition
    }

    applog("App State changed from %s to %s", appStateStrings[g_currentAppState], appStateStrings[g_currentAppState]);
    g_currentAppState = newState;
    g_lastActivityTime = millis();  // Reset activity timer
    lastStateChangeTime = millis(); // 状態変更時刻を更新
  }
}

void startVibrationSync(unsigned long duration_ms) {
  if (!VIBRA) {
    applog("Vibration is OFF. Skipping startVibrationSync.");
    return;
  }
  applog("startVibrationSync %dms", duration_ms);
  digitalWrite(MOTOR_GPIO, HIGH);
  delay(duration_ms);
  digitalWrite(MOTOR_GPIO, LOW);
}

void goDeepSleep() {
  float usagePercentage = getLittleFSUsagePercentage();

  updateDisplay("");

  // 録音開始や録音停止ボタンを押したらディープスリープ復帰するコード
  esp_sleep_enable_ext1_wakeup_io(BUTTON_PIN_BITMASK(REC_BUTTON_GPIO), ESP_EXT1_WAKEUP_ANY_HIGH);
  rtc_gpio_pulldown_en(REC_BUTTON_GPIO);
  rtc_gpio_pullup_dis(REC_BUTTON_GPIO);

  esp_sleep_enable_ext1_wakeup_io(BUTTON_PIN_BITMASK(UPLOAD_BUTTON_GPIO), ESP_EXT1_WAKEUP_ANY_HIGH);
  rtc_gpio_pulldown_en(UPLOAD_BUTTON_GPIO);
  rtc_gpio_pullup_dis(UPLOAD_BUTTON_GPIO);

  esp_sleep_enable_ext1_wakeup_io(BUTTON_PIN_BITMASK(USB_DETECT_PIN), ESP_EXT1_WAKEUP_ANY_HIGH);
  rtc_gpio_pulldown_en(USB_DETECT_PIN);
  rtc_gpio_pullup_dis(USB_DETECT_PIN);

  wifiSetSleep(true);  // wifiモデムスリープ
  displaySleep(true); // LCDスリープ
  // esp_sleep_enable_timer_wakeup(TIME_TO_SLEEP * uS_TO_S_FACTOR); でタイマー復帰できる：今は使わないけどこのメモ消さないで

  esp_deep_sleep_start();
}

bool isUploadOrSyncNeeded() {
  return !g_hasTimeBeenSynchronized || g_audioFileCount > 0 || g_isForceUpload;
}

void tryUploadAndSync() {
  if (connectToWiFi()) {
    applog("WiFi connected");
    updateDisplay("WiFi OK"); // Debug message on OLED
    synchronizeTime(true); // Perform NTP sync after WiFi is connected
    execUpload();
    g_audioFileCount = countAudioFiles(); // Update file counts after upload try
  } else {
    applog("WiFi not connected.");
    updateDisplay("WiFi Fail"); // Debug message on OLED
    synchronizeTime(false); // Get time from RTC as a fallback if WiFi fails
  }
}

void writeAudioBufferToFile() {
  size_t available_data = 0;
  size_t contiguous_block_size = 0;

  xSemaphoreTake(g_buffer_mutex, portMAX_DELAY);
  if (g_buffer_head >= g_buffer_tail) {
    available_data = g_buffer_head - g_buffer_tail;
  } else {
    available_data = g_audio_buffer.size() - g_buffer_tail + g_buffer_head;
  }

  if (available_data > 0) {
    if (g_buffer_head > g_buffer_tail) {
      contiguous_block_size = available_data;
    } else {
      contiguous_block_size = g_audio_buffer.size() - g_buffer_tail;
    }
    
    size_t written_bytes = g_audioFile.write((const uint8_t*)&g_audio_buffer[g_buffer_tail], contiguous_block_size * sizeof(int16_t));
    g_totalBytesRecorded += written_bytes;
    g_buffer_tail = (g_buffer_tail + (written_bytes / sizeof(int16_t))) % g_audio_buffer.size();
  }
  xSemaphoreGive(g_buffer_mutex);
}

void flushAudioBufferToFile() {
  applog("Flushing audio buffer to file...");
  while (g_buffer_head != g_buffer_tail) {
    writeAudioBufferToFile();
    vTaskDelay(pdMS_TO_TICKS(10)); // Give some time for the file write to happen
  }
  applog("Buffer flushed.");
}

void startRecording() {
  if (!checkFreeSpace()) {
    applog("Not enough free space to start recording. Entering IDLE state.");
    setAppState(IDLE);
    return;
  }

  setAppState(REC, false);
  g_enable_logging = true;
  applog("Recording started at %ums", millis() - g_boot_time_ms);

  // --- Start pre-buffering and give immediate feedback ---
  xSemaphoreTake(g_buffer_mutex, portMAX_DELAY);
  g_buffer_head = 0;
  g_buffer_tail = 0;
  xSemaphoreGive(g_buffer_mutex);
  g_is_buffering = true; // Signal I2S task to start buffering

  startVibrationSync(VIBRA_REC_START_MS); // Vibrate immediately
  onboard_led(true);
  updateDisplay("");

  // --- Perform slower file operations after feedback ---
  generateFilenameFromRTC(g_audio_filename, sizeof(g_audio_filename));
  applog("Opening file %s for writing...", g_audio_filename);

  g_audioFile = LittleFS.open(g_audio_filename, FILE_WRITE);
  if (!g_audioFile) {
    applog("Failed to open file for writing!");
    g_is_buffering = false; // Stop buffering
    setAppState(DSLEEP);
    return;
  }

  writeWavHeader(g_audioFile, 0); // Write a placeholder header
  g_audioFileCount = countAudioFiles();

  g_scheduledStopTimeMillis = millis() + (unsigned long)REC_MAX_S * 1000;
  g_totalBytesRecorded = 0;
  applog("File opened. Now writing buffered and live audio data...");
}

void stopRecording() {
  applog("Stopping recording...");
  g_is_buffering = false; // Signal I2S task to stop writing to the buffer
  
  flushAudioBufferToFile(); // Write any remaining data from the buffer to the file

  onboard_led(false);
  if (g_audioFile) {
    updateWavHeader(g_audioFile, g_totalBytesRecorded);
    g_audioFile.close();
    applog("File closed. Total bytes recorded: %u", g_totalBytesRecorded);
    finalizeRecording(); // Check file size and delete if too short
  } else {
    applog("Error: Audio file was not open.");
  }

  updateDisplay("");
  setAppState(IDLE);
  startVibrationSync(VIBRA_REC_STOP_MS);
}

void handleIdle() {
  static unsigned long lastDisplayUpdateTime = 0;

  if (millis() - lastDisplayUpdateTime > 200) {
    float usagePercentage = getLittleFSUsagePercentage();
    updateDisplay("");
    lastDisplayUpdateTime = millis();
  }

  if (digitalRead(REC_BUTTON_GPIO) == HIGH) { // Recording switch is pressed
    startRecording();
    return;
  }

  if (digitalRead(UPLOAD_BUTTON_GPIO) == HIGH) {
    startVibrationSync(VIBRA_STARTUP_MS);
    g_isForceUpload = true;
    setAppState(UPLOAD); // UPLOAD状態に遷移
  }
  
  // Only force UPLOAD if we are still in IDLE state (i.e., recording was not just started)
  if (g_currentAppState == IDLE && isConnectUSB() && !isWiFiConnected()) {
    g_isForceUpload = true; // 強制UPLOAD
    setAppState(UPLOAD, false);
  }
}

void handleRec() {
  // If recording switch is turned off, stop recording
  if (digitalRead(REC_BUTTON_GPIO) == LOW) {
    applog("Recording switch turned OFF. Stopping recording.");
    stopRecording();
    g_scheduledStopTimeMillis = 0;  // Reset for next recording
    return;
  }

  // Check if it's time to stop recording
  if (g_scheduledStopTimeMillis > 0 && millis() >= g_scheduledStopTimeMillis) {
    applog("Scheduled stop time reached. Stopping recording.");
    stopRecording();
    g_scheduledStopTimeMillis = 0;  // Reset for next recording
  } else {
    // Continuously write buffered data to the file
    writeAudioBufferToFile();
  }
  g_lastActivityTime = millis();  // Reset activity timer while recording
}

void handleUpload() {
  applog("Performing post-recording actions...");
  static unsigned long lastUploadTryTime = 0; // To track last upload try time

  while (isUploadOrSyncNeeded()) {
    g_isForceUpload = false;
    if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
      applog("Start button pressed during upload. Cancelling upload and starting recording.");
      setAppState(IDLE);
      return;
    }

    if (millis() - lastUploadTryTime >= UPLOAD_RETRY_DELAY_MS || lastUploadTryTime == 0) {
      tryUploadAndSync();
      lastUploadTryTime = millis(); // Reset timer after try
    }
    
    if (!isConnectUSB()) {
      applog("USB disconnected during upload retry loop. Exiting.");
      break;
    }

    if (!isUploadOrSyncNeeded()) {
      break;
    }
    g_lastActivityTime = millis();  // Reset activity timer after stopping recording or writing data
    yield(); // Allow other tasks to run
  }

  lastUploadTryTime = 0; // Reset lastUploadTryTime when exiting the upload state
  setAppState(IDLE, false);
}

void handleSetup() {
  static unsigned long lastDisplayUpdateTime = 0;

  if (millis() - lastDisplayUpdateTime > 200) {
    updateDisplay("");
    lastDisplayUpdateTime = millis();
  }

  // If in SETUP state, not connected via BLE, and inactive, go to deep sleep
  if ((millis() - g_lastActivityTime > DEEP_SLEEP_DELAY_MS) && (pBLEServer == nullptr || pBLEServer->getConnectedCount() == 0)) {
    setAppState(DSLEEP, false);
  }
}

void wakeupLogic() {
  esp_sleep_wakeup_cause_t wakeup_reason = esp_sleep_get_wakeup_cause();

  switch (wakeup_reason) {
    case ESP_SLEEP_WAKEUP_EXT1: {
      uint64_t wakeup_pin_mask = esp_sleep_get_ext1_wakeup_status();
      if (wakeup_pin_mask & BUTTON_PIN_BITMASK(REC_BUTTON_GPIO)) {
        if (digitalRead(REC_BUTTON_GPIO) == HIGH) { // If button is currently pressed
            // This is the fast path to recording. Logging remains disabled until startRecording() enables it.
            startRecording(); // Directly start recording
        } else { // If button is not pressed (e.g., was pressed and released quickly)
            g_enable_logging = true; // Enable logging
            applog("Wakeup by REC button, but not pressed now. Going to IDLE. Wakeup reason: %d", wakeup_reason);
            setAppState(IDLE, false);
        }
      } else {
        g_enable_logging = true; // Enable logging for other buttons
        applog("Wakeup was caused by: %d", wakeup_reason);
        if (wakeup_pin_mask & BUTTON_PIN_BITMASK(UPLOAD_BUTTON_GPIO)) {
          applog("UPLOAD Button pressed on wake-up");
          setAppState(UPLOAD, false);
        } else if (wakeup_pin_mask & BUTTON_PIN_BITMASK(USB_DETECT_PIN)) {
          applog("USB connected on wake-up");
          setAppState(UPLOAD, false);
        }
      }
      break;
    }
    case ESP_SLEEP_WAKEUP_EXT0:
    case ESP_SLEEP_WAKEUP_TIMER:
    case ESP_SLEEP_WAKEUP_ULP:
    default:
      g_enable_logging = true; // Enable logging for all other cases
      applog("Wakeup was caused by: %d", wakeup_reason);
      setAppState(IDLE, false);
      startVibrationSync(VIBRA_STARTUP_MS);
      break;
  }
}

void initPins() {
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH); // LED消灯

  pinMode(REC_BUTTON_GPIO, INPUT_PULLDOWN);
  pinMode(UPLOAD_BUTTON_GPIO, INPUT_PULLDOWN);

  pinMode(MOTOR_GPIO, OUTPUT);
  pinMode(USB_DETECT_PIN, INPUT);  // Initialize USB connect pin

}

void initAdc() {
  // Initialize ADC for battery voltage reading
  analogReadResolution(12); // Set ADC resolution to 12 bits (0-4095)
  analogSetPinAttenuation(BATTERY_DIV_PIN, ADC_11db); // Set attenuation for 0-3.3V range
  g_currentBatteryVoltage = getBatteryVoltage(); // Initial read to populate currentBatteryVoltage
}

void initRTCtime() {
  struct tm timeinfo;
  if (!getValidRtcTime(&timeinfo)) { // Check RTC time at startup
    setRtcToDefaultTime();
  }
}

void setup() {
  g_enable_logging = LOG_AT_BOOT;

  g_boot_time_ms = millis();
  Serial.begin(SERIAL_BAUD_RATE);

  //serialWait(); // コンソールデバッグ用：このコメント行を消さないでください

  setenv("TZ", "JST-9", 1);
  tzset();
  
  initPins();

  
  initAdc();
  
  initRTCtime();
  
  initLittleFS();
  
  if (!loadSettingsFromLittleFS()) {
    setAppState(SETUP, false);
  } else {
    updateMinAudioFileSize(); // Initialize MIN_AUDIO_FILE_SIZE_BYTES after loading settings
    wakeupLogic();
  }
  
  start_ble_server();
  
  initSSD();

  // --- Initialize Audio Buffering System ---
  g_buffer_mutex = xSemaphoreCreateMutex();
  const int buffer_seconds = 3;
  g_audio_buffer.resize(I2S_SAMPLE_RATE * buffer_seconds);
  applog("Audio buffer size: %d for %d seconds", g_audio_buffer.size(), buffer_seconds);
  xTaskCreatePinnedToCore(i2s_read_task, "I2SReaderTask", 4096, NULL, 10, &g_i2s_reader_task_handle, 1);

  initI2SMicrophone();

  initWifi();
  
  g_lastActivityTime = millis();  // Reset activity timer after setup or deletion
}

void loop() {
  if (g_start_log_transfer) {
    handleLogTransfer();
  }

  switch (g_currentAppState) {
    case IDLE:
      handleIdle();
      break;

    case REC:
      handleRec();
      break;

    case UPLOAD:
      handleUpload();
      break;

    case SETUP:
      handleSetup();
      break;

    case DSLEEP:
      goDeepSleep();
      break;
  }

  if (g_currentAppState == IDLE && (millis() - g_lastActivityTime > DEEP_SLEEP_DELAY_MS) && !isConnectUSB() && (pBLEServer == nullptr || pBLEServer->getConnectedCount() == 0)) {
    setAppState(DSLEEP, false);
  }
  g_currentBatteryVoltage = getBatteryVoltage();
}
