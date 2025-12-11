#include "fastrec_alt.h"

#include <time.h>
#include <sys/time.h>
#include <Base64.h>
#include "driver/rtc_io.h"
#include "esp_bt.h"

#include "esp_sleep.h"
#include <Wire.h>

#define BUTTON_PIN_BITMASK(GPIO) (1ULL << GPIO)

// --- Function Implementations ---
void serialWait() { // check_unused:ignore
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
    {INIT, SETUP},
    {IDLE, REC},
    {REC, IDLE},
    {IDLE, DSLEEP},
    {SETUP, DSLEEP}
};
const size_t NUM_VALID_TRANSITIONS = sizeof(validTransitions) / sizeof(validTransitions[0]);

void setAppState(AppState newState, bool applyDebounce=true) {
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

    // Disconnect any active BLE clients if moving to a non-IDLE state
    if (newState != IDLE) {
      stop_ble_advertising(); // Stop advertising immediately
      disconnect_ble_clients(); // Then disconnect any connected clients
    }

    applog("App State changed from %s to %s", appStateStrings[g_currentAppState], appStateStrings[newState]);
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
      
  stop_ble_advertising();

  updateDisplay("");

  // 録音開始や録音停止ボタンを押したらディープスリープ復帰するコード
  esp_sleep_enable_ext1_wakeup_io(BUTTON_PIN_BITMASK(REC_BUTTON_GPIO), ESP_EXT1_WAKEUP_ANY_HIGH);
  rtc_gpio_pulldown_en(REC_BUTTON_GPIO);
  rtc_gpio_pullup_dis(REC_BUTTON_GPIO);

  esp_sleep_enable_ext1_wakeup_io(BUTTON_PIN_BITMASK(USB_DETECT_PIN), ESP_EXT1_WAKEUP_ANY_HIGH);
  rtc_gpio_pulldown_en(USB_DETECT_PIN);
  rtc_gpio_pullup_dis(USB_DETECT_PIN);

  displaySleep(true); // LCDスリープ
  esp_sleep_enable_timer_wakeup(DEEP_SLEEP_CYCLE_MS * 1000); // DEEP_SLEEP_CYCLE_MS is in milliseconds, convert to microseconds
  esp_deep_sleep_start();
}

void writeAudioBufferToFile() {
  xSemaphoreTake(g_buffer_mutex, portMAX_DELAY);

  if (g_buffer_tail == g_buffer_head) {
    xSemaphoreGive(g_buffer_mutex);
    return; // No data to write
  }

  size_t total_written_bytes = 0;

  if (g_buffer_head > g_buffer_tail) {
    // Case 1: Data is in a single contiguous block
    size_t block_size = g_buffer_head - g_buffer_tail;
    total_written_bytes = g_audioFile.write((const uint8_t*)&g_audio_buffer[g_buffer_tail], block_size * sizeof(int16_t));
    g_buffer_tail += (total_written_bytes / sizeof(int16_t));
  } else {
    // Case 2: Data is in two blocks (wrapped around)
    // Block 1: from tail to the end of the buffer
    size_t block1_size = g_audio_buffer.size() - g_buffer_tail;
    size_t written_bytes1 = g_audioFile.write((const uint8_t*)&g_audio_buffer[g_buffer_tail], block1_size * sizeof(int16_t));
    
    if (written_bytes1 == block1_size * sizeof(int16_t)) {
      // If block 1 was written completely, proceed to block 2
      g_buffer_tail = 0;
      total_written_bytes += written_bytes1;

      // Block 2: from the start of the buffer to head
      size_t block2_size = g_buffer_head;
      if (block2_size > 0) {
        size_t written_bytes2 = g_audioFile.write((const uint8_t*)&g_audio_buffer[0], block2_size * sizeof(int16_t));
        g_buffer_tail += (written_bytes2 / sizeof(int16_t));
        total_written_bytes += written_bytes2;
      }
    } else {
      // If block 1 was not fully written, just update tail and exit
      g_buffer_tail += (written_bytes1 / sizeof(int16_t));
      total_written_bytes += written_bytes1;
    }
  }
  
  g_totalBytesRecorded += total_written_bytes;
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

void handleIdle() {
  static unsigned long lastDisplayUpdateTime = 0;

  start_ble_advertising();

  if (millis() - lastDisplayUpdateTime > 200) {
    float usagePercentage = getLittleFSUsagePercentage();
    updateDisplay("");
    lastDisplayUpdateTime = millis();
  }

  if (digitalRead(REC_BUTTON_GPIO) == HIGH) { // Recording switch is pressed
    setLcdBrightness(0xFF); // Brighten LCD
    startRecording();
    return;
  }

  // Go to deep sleep if idle for a while, not connected to USB, and not in BLE setup
  if ((millis() - g_lastActivityTime > DEEP_SLEEP_DELAY_MS) && 
      !isConnectUSB() && 
      (!isBLEConnected() || g_audioFileCount == 0)
    ) {
    setAppState(DSLEEP, false);
  }
}

void handleRec() {
      
  stop_ble_advertising();

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

void handleSetup() {
  static unsigned long lastDisplayUpdateTime = 0;
      
  start_ble_advertising();

  if (millis() - lastDisplayUpdateTime > 200) {
    updateDisplay("");
    lastDisplayUpdateTime = millis();
  }

  // If in SETUP state, not connected via BLE, and inactive, go to deep sleep
  if ((millis() - g_lastActivityTime > DEEP_SLEEP_DELAY_MS) && (!isBLEConnected() || g_audioFileCount == 0)) {
    setAppState(DSLEEP, false);
  }
}

void wakeupLogic() {
  esp_sleep_wakeup_cause_t wakeup_reason = esp_sleep_get_wakeup_cause();

  switch (wakeup_reason) {
    case ESP_SLEEP_WAKEUP_EXT1: {
      uint64_t wakeup_pin_mask = esp_sleep_get_ext1_wakeup_status();
      if (wakeup_pin_mask & BUTTON_PIN_BITMASK(REC_BUTTON_GPIO)) {
        setLcdBrightness(0xFF); // RECボタンでウェイクアップした場合は、常にLCDを明るくする
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

  // If not restored from deep sleep, or stored time was invalid, proceed with normal initialization
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
  
  // Configure internal RTC with timezone, even if not using NTP for initial sync.
  // This ensures time functions behave correctly with the specified timezone.
  const long  gmtOffset_sec = 9 * 3600; // JST is UTC+9
  const int   daylightOffset_sec = 0;   // No daylight saving in JST
  
  initPins();

  initRTCtime();
  
  initLittleFS();
    
  initSSD();
    
  if (!loadSettingsFromLittleFS()) {
    setAppState(SETUP, false);
    g_lastActivityTime = millis();  // Reset activity timer after setup or deletion
    return;
  }

  initI2SMicrophone();

  start_ble_server();
  initAdc();

  wakeupLogic();

  g_lastActivityTime = millis();  // Reset activity timer after setup or deletion
}

void loop() {
  transferFileChunked();
  switch (g_currentAppState) {
    case IDLE:
      handleIdle();
      break;
    case REC:
      handleRec();
      break;
    case SETUP:
      handleSetup();
      break;
    case DSLEEP:
      goDeepSleep();
      break;
  }
  g_currentBatteryVoltage = getBatteryVoltage();
}
