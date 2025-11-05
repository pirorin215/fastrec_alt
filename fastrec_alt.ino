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
  app_log_i("Serial init.\n");
}

// Define valid state transitions
const AppState validTransitions[][2] = {
    {INIT, IDLE},
    {INIT, REC},
    {INIT, UPLOAD},
    {IDLE, REC},
    {REC, IDLE},
    {IDLE, UPLOAD},
    {UPLOAD, IDLE},
    {IDLE, DSLEEP}
};
const size_t NUM_VALID_TRANSITIONS = sizeof(validTransitions) / sizeof(validTransitions[0]);

void setAppState(AppState newState, bool applyDebounce = true) {
  static unsigned long lastStateChangeTime = 0; // 状態変更のデバウンス用

  if (g_currentAppState != newState) {
    // デバウンス時間内に連続して状態変更が要求された場合は無視する
    if (applyDebounce && (millis() - lastStateChangeTime < STATE_CHANGE_DEBOUNCE_MS)) {
      app_log_i("Ignoring rapid state change request to %s (current: %s)\r\n", appStateStrings[newState], appStateStrings[g_currentAppState]);
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
      app_log_i("ERROR: Attempted invalid state transition from %s to %s. Ignoring request.\r\n",
                    appStateStrings[g_currentAppState], appStateStrings[newState]);
      return; // Ignore invalid transition
    }

    app_log_i("App State changed from %s to %s\r\n", appStateStrings[g_currentAppState], appStateStrings[newState]);
    g_currentAppState = newState;
    g_lastActivityTime = millis();  // Reset activity timer
    lastStateChangeTime = millis(); // 状態変更時刻を更新
  }
}

void startVibrationSync(unsigned long duration_ms) {
  app_log_i("startVibrationSync %dms\r\n", duration_ms);
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
    app_log_i("WiFi connected\n");
    updateDisplay("WiFi OK"); // Debug message on OLED
    synchronizeTime(true); // Perform NTP sync after WiFi is connected
    execUpload();
    g_audioFileCount = countAudioFiles(); // Update file counts after upload try
  } else {
    app_log_i("WiFi not connected.\n");
    updateDisplay("WiFi Fail"); // Debug message on OLED
    synchronizeTime(false); // Get time from RTC as a fallback if WiFi fails
  }
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
    app_log_i("Recording switch turned OFF. Stopping recording.\n");
    stopRecording();
    g_scheduledStopTimeMillis = 0;  // Reset for next recording
    return;
  }

  // Check if it's time to stop recording
  if (g_scheduledStopTimeMillis > 0 && millis() >= g_scheduledStopTimeMillis) {
    app_log_i("Scheduled stop time reached. Stopping recording.\n");
    stopRecording();
    g_scheduledStopTimeMillis = 0;  // Reset for next recording
  } else {
    // Continue recording if not yet time to stop
    addRecording();
  }
  g_lastActivityTime = millis();  // Reset activity timer after stopping recording or writing data
}

void handleUpload() {
  app_log_i("Performing post-recording actions...\n");
  static unsigned long lastUploadTryTime = 0; // To track last upload try time

  while (isUploadOrSyncNeeded()) {
    g_isForceUpload = false;
    if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
      app_log_i("Start button pressed during upload. Cancelling upload and starting recording.\n");
      setAppState(IDLE);
      return;
    }

    if (millis() - lastUploadTryTime >= UPLOAD_RETRY_DELAY_MS || lastUploadTryTime == 0) {
      tryUploadAndSync();
      lastUploadTryTime = millis(); // Reset timer after try
    }
    
    if (!isConnectUSB()) {
      app_log_i("USB disconnected during upload retry loop. Exiting.\n");
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

void wakeupLogic() {
  esp_sleep_wakeup_cause_t wakeup_reason = esp_sleep_get_wakeup_cause();

  log_i("Wakeup was caused by: %d\r\n", wakeup_reason);
       
  switch (wakeup_reason) {
    case ESP_SLEEP_WAKEUP_EXT1: {
      uint64_t wakeup_pin_mask = esp_sleep_get_ext1_wakeup_status();
      if (wakeup_pin_mask & BUTTON_PIN_BITMASK(REC_BUTTON_GPIO)) {
        app_log_i("Start Button caused wake-up.\n");
        if (digitalRead(REC_BUTTON_GPIO) == HIGH) { // If button is currently pressed
            startRecording();
        } else { // If button is not pressed (e.g., was pressed and released quickly)
            setAppState(IDLE, false);
        }
      } else if (wakeup_pin_mask & BUTTON_PIN_BITMASK(UPLOAD_BUTTON_GPIO)) {
        app_log_i("Stop Button pressed on wake-up\n");
        setAppState(UPLOAD, false);
      } else if (wakeup_pin_mask & BUTTON_PIN_BITMASK(USB_DETECT_PIN)) {
        app_log_i("USB connected on wake-up\n");
        setAppState(UPLOAD, false);
      }
      break;
    }
    case ESP_SLEEP_WAKEUP_EXT0:
      break;
    case ESP_SLEEP_WAKEUP_TIMER:
      break;
    case ESP_SLEEP_WAKEUP_ULP:
      break;
    default:
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
  Serial.begin(SERIAL_BAUD_RATE);

  //serialWait(); // コンソールデバッグ用：このコメント行を消さないでください

  setenv("TZ", "JST-9", 1);
  tzset();
  
  initPins();

  
  initAdc();
  
  initRTCtime();
  
  initLittleFS();
  
  loadSettingsFromLittleFS();
  updateMinAudioFileSize(); // Initialize MIN_AUDIO_FILE_SIZE_BYTES after loading settings
  
  start_ble_server();
  
  initSSD();

  initI2SMicrophone();

  initWifi();

  wakeupLogic();
  
  g_lastActivityTime = millis();  // Reset activity timer after setup or deletion
}

void loop() {

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

    case DSLEEP:
      goDeepSleep();
      break;
  }

  if (g_currentAppState == IDLE && (millis() - g_lastActivityTime > DEEP_SLEEP_DELAY_MS) && !isConnectUSB() && (pBLEServer == nullptr || pBLEServer->getConnectedCount() == 0)) {
    setAppState(DSLEEP, false);
  }
  g_currentBatteryVoltage = getBatteryVoltage();
}

