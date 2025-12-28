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

  // モーターを確実にOFF
  digitalWrite(MOTOR_GPIO, LOW);

  // 録音開始や録音停止ボタンを押したらディープスリープ復帰するコード
  esp_sleep_enable_ext1_wakeup_io(BUTTON_PIN_BITMASK(REC_BUTTON_GPIO), ESP_EXT1_WAKEUP_ANY_HIGH);
  rtc_gpio_pulldown_en(REC_BUTTON_GPIO);
  rtc_gpio_pullup_dis(REC_BUTTON_GPIO);

  esp_sleep_enable_ext1_wakeup_io(BUTTON_PIN_BITMASK(USB_DETECT_PIN), ESP_EXT1_WAKEUP_ANY_HIGH);
  rtc_gpio_pulldown_en(USB_DETECT_PIN);
  rtc_gpio_pullup_dis(USB_DETECT_PIN);

  displaySleep(true); // LCDスリープ

  // I2CピンをハイインピーダンスにしてLCDのリーク電流を削減
  pinMode(LCD_SDA_GPIO, INPUT);
  pinMode(LCD_SCL_GPIO, INPUT);

  // I2Sピンをディスエーブル（マイクのリーク電流対策）
  gpio_reset_pin(I2S_BCLK_PIN);
  gpio_reset_pin(I2S_DOUT_PIN);
  gpio_reset_pin(I2S_LRCK_PIN);

  // DEEP_SLEEP_CYCLE_MINUTESの値で動作を切り替え
  if (DEEP_SLEEP_CYCLE_MINUTES == 0) {
    // 毎正時に復帰するモード
    time_t now;
    struct tm timeinfo;
    time(&now);
    localtime_r(&now, &timeinfo);

    // 次の正時まで何秒か計算
    int minutes_until_next_hour = 59 - timeinfo.tm_min;
    int seconds_until_next_hour = 60 - timeinfo.tm_sec;
    int total_seconds = (minutes_until_next_hour * 60) + seconds_until_next_hour;

    // 残り時間が5分未満の場合は、次の次の正時まで待つ（頻繁な復帰を避ける）
    if (total_seconds < 300) {
      total_seconds += 3600; // 1時間追加
    }

    // マイクロ秒に変換
    uint64_t sleep_time_us = (uint64_t)total_seconds * 1000000ULL;

    applog("Next wakeup in %d seconds (at next hour: %02d:00)", total_seconds, (timeinfo.tm_hour + (total_seconds / 3600)) % 24);
    esp_sleep_enable_timer_wakeup(sleep_time_us);
  } else {
    // 従来の固定時間モード
    applog("Next wakeup in %lu minutes", DEEP_SLEEP_CYCLE_MINUTES);
    esp_sleep_enable_timer_wakeup(DEEP_SLEEP_CYCLE_MS * 1000); // DEEP_SLEEP_CYCLE_MS is in milliseconds, convert to microseconds
  }

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

// --- ADPCM Block-based Encoding (Refactored for dual task) ---

// This function is now only responsible for encoding a block of PCM to ADPCM
// and pushing it into the intermediate g_adpcm_buffer.
void encode_and_push_adpcm_block(int16_t* pcm_samples, int num_samples) {
    uint8_t adpcm_block[ADPCM_BLOCK_SIZE];
    memset(adpcm_block, 0, ADPCM_BLOCK_SIZE);

    ImaAdpcmState block_state;
    block_state.predictor = pcm_samples[0];
    block_state.step_index = 0;

    adpcm_block[0] = block_state.predictor & 0xFF;
    adpcm_block[1] = (block_state.predictor >> 8) & 0xFF;
    adpcm_block[2] = block_state.step_index;
    adpcm_block[3] = 0; // Reserved

    bool high_nibble = true;
    int adpcm_idx = 4; // Start after header
    for (int i = 1; i < num_samples; i++) {
        uint8_t code = ima_adpcm_encode(pcm_samples[i], block_state);
        if (high_nibble) {
            adpcm_block[adpcm_idx] = code;
        } else {
            adpcm_block[adpcm_idx] |= (code << 4);
            adpcm_idx++;
        }
        high_nibble = !high_nibble;
    }
    
    // --- Push to ADPCM buffer ---
    xSemaphoreTake(g_adpcm_buffer_mutex, portMAX_DELAY);
    size_t next_head = (g_adpcm_buffer_head + 1) % ADPCM_BUFFER_BLOCKS;
    
    // Wait if the buffer is full
    while (next_head == g_adpcm_buffer_tail) {
        xSemaphoreGive(g_adpcm_buffer_mutex);
        vTaskDelay(pdMS_TO_TICKS(5)); // Wait for the writer task to consume data
        xSemaphoreTake(g_adpcm_buffer_mutex, portMAX_DELAY);
        next_head = (g_adpcm_buffer_head + 1) % ADPCM_BUFFER_BLOCKS;
    }
    
    memcpy(&g_adpcm_buffer[g_adpcm_buffer_head * ADPCM_BLOCK_SIZE], adpcm_block, ADPCM_BLOCK_SIZE);
    g_adpcm_buffer_head = next_head;
    
    xSemaphoreGive(g_adpcm_buffer_mutex);
}

// This function is now the core of the audio_encoder_task.
// It pulls data from the PCM buffer and fills a local block buffer.
void process_pcm_to_adpcm(int16_t* pcm_block_buffer, int& pcm_block_idx) {
    xSemaphoreTake(g_buffer_mutex, portMAX_DELAY);
    if (g_buffer_tail == g_buffer_head) {
        xSemaphoreGive(g_buffer_mutex);
        return; // No data to process
    }

    while (g_buffer_tail != g_buffer_head && pcm_block_idx < ADPCM_SAMPLES_PER_BLOCK) {
        pcm_block_buffer[pcm_block_idx++] = g_audio_buffer[g_buffer_tail];
        g_buffer_tail = (g_buffer_tail + 1) % g_audio_buffer.size();
        g_totalSamplesRecorded++;

        if (pcm_block_idx == ADPCM_SAMPLES_PER_BLOCK) {
            // Release PCM buffer mutex before heavy encoding to avoid holding it for too long
            xSemaphoreGive(g_buffer_mutex);
            
            encode_and_push_adpcm_block(pcm_block_buffer, ADPCM_SAMPLES_PER_BLOCK);
            
            // Reset for next block
            pcm_block_idx = 0; 

            // Re-take mutex to continue processing
            xSemaphoreTake(g_buffer_mutex, portMAX_DELAY);
        }
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

void flushAudioBufferToFileADPCM() {
  applog("Flushing ADPCM audio buffer is now handled by writer task completion.");
  // This function is now effectively a no-op. The stopRecording function
  // will wait for the file_writer_task to finish draining the adpcm_buffer.
}

// New task dedicated to writing encoded ADPCM data from the intermediate buffer to the file
void file_writer_task(void *pvParameters) {
    applog("File writer task started on core %d", xPortGetCoreID());

    while (g_is_buffering || g_adpcm_buffer_head != g_adpcm_buffer_tail) {
        if (g_adpcm_buffer_head == g_adpcm_buffer_tail) {
            // Buffer is empty, wait a bit
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }

        xSemaphoreTake(g_adpcm_buffer_mutex, portMAX_DELAY);

        size_t blocks_available = 0;
        size_t tail = g_adpcm_buffer_tail;
        size_t head = g_adpcm_buffer_head;
        
        if (head >= tail) {
            blocks_available = head - tail;
        } else { // Wrapped
            blocks_available = ADPCM_BUFFER_BLOCKS - tail;
        }

        if (blocks_available > 0) {
            size_t bytes_to_write = blocks_available * ADPCM_BLOCK_SIZE;
            size_t bytes_written = g_audioFile.write(&g_adpcm_buffer[tail * ADPCM_BLOCK_SIZE], bytes_to_write);
            if (bytes_written > 0) {
                g_totalBytesRecorded += bytes_written;
                g_adpcm_buffer_tail = (tail + (bytes_written / ADPCM_BLOCK_SIZE)) % ADPCM_BUFFER_BLOCKS;
            }
        }

        xSemaphoreGive(g_adpcm_buffer_mutex);
    }

    applog("File writer task finished.");
    g_file_writer_task_handle = NULL;
    vTaskDelete(NULL);
}

// This task is now the 'encoder' task for ADPCM, or the 'writer' task for PCM
void audio_writer_task(void *pvParameters) {
    if (USE_ADPCM) {
        // --- ADPCM Encoder Task Logic ---
        applog("Audio encoder task (ADPCM) started on core %d", xPortGetCoreID());
        
        int16_t* pcm_block_buffer = (int16_t*)malloc(sizeof(int16_t) * ADPCM_SAMPLES_PER_BLOCK);
        int pcm_block_idx = 0;

        while (g_is_buffering) {
            process_pcm_to_adpcm(pcm_block_buffer, pcm_block_idx);
            vTaskDelay(pdMS_TO_TICKS(10)); // Yield to other tasks
        }

        // After buffering stops, drain the remaining samples from the PCM buffer
        applog("Draining final PCM buffer for ADPCM encoding...");
        process_pcm_to_adpcm(pcm_block_buffer, pcm_block_idx);

        // Handle the very last partial block
        if (pcm_block_idx > 0) {
            applog("Encoding last partial ADPCM block with %d samples.", pcm_block_idx);
            // Pad the rest of the block with silence (last sample value)
            for (int i = pcm_block_idx; i < ADPCM_SAMPLES_PER_BLOCK; i++) {
                pcm_block_buffer[i] = pcm_block_buffer[pcm_block_idx > 0 ? pcm_block_idx - 1 : 0];
            }
            encode_and_push_adpcm_block(pcm_block_buffer, ADPCM_SAMPLES_PER_BLOCK);
        }
        
        free(pcm_block_buffer);
        applog("Audio encoder task (ADPCM) finished.");

    } else {
        // --- PCM Writer Task Logic (Unchanged) ---
        applog("Audio writer task (PCM) started on core %d", xPortGetCoreID());
        while (g_is_buffering) {
            if (g_buffer_head != g_buffer_tail) {
                writeAudioBufferToFile();
            }
            vTaskDelay(pdMS_TO_TICKS(10));
        }

        // After buffering stops, drain the remaining samples
        applog("Draining final audio buffer (PCM)...");
        while (g_buffer_head != g_buffer_tail) {
            writeAudioBufferToFile();
            vTaskDelay(pdMS_TO_TICKS(10));
        }
    }

    applog("Audio writer task finished.");
    g_audio_writer_task_handle = NULL;
    vTaskDelete(NULL);
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
  }
  // Writing is now handled by audio_writer_task
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

  applog("Wakeup was caused by: %d", wakeup_reason);

  g_enable_logging = true;

  switch (wakeup_reason) {
    case ESP_SLEEP_WAKEUP_EXT1: {
      uint64_t wakeup_pin_mask = esp_sleep_get_ext1_wakeup_status();
      if (wakeup_pin_mask & BUTTON_PIN_BITMASK(REC_BUTTON_GPIO)) {
        if (digitalRead(REC_BUTTON_GPIO) == HIGH) { // If button is currently pressed
            g_enable_logging = false;
            startRecording();
        } else {
            g_enable_logging = true;
        }
      }
      setLcdBrightness(0xFF); // ボタンでウェイクアップした場合はLCDを明るくする
      break;
    }
    case ESP_SLEEP_WAKEUP_EXT0:
      break;
    case ESP_SLEEP_WAKEUP_TIMER:
      break;
    case ESP_SLEEP_WAKEUP_ULP:
      break;
    default:
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
