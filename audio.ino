#include "fastrec_alt.h"

#include <driver/i2s.h>

// Function to write a placeholder WAV header
void writeWavHeader(File& file, uint32_t dataSize) {
  WavHeader header;

  memcpy(header.riff, "RIFF", 4);
  header.chunkSize = dataSize + 36;  // 36 bytes for the rest of the header
  memcpy(header.wave, "WAVE", 4);
  memcpy(header.fmt, "fmt ", 4);
  header.subchunk1Size = 16;
  header.audioFormat = 1;  // PCM
  header.numChannels = 1;  // Always mono as per I2S configuration
  header.sampleRate = I2S_SAMPLE_RATE;
  header.bitsPerSample = 16;  // Samples are converted to 16-bit before writing
  header.byteRate = header.sampleRate * header.numChannels * header.bitsPerSample / 8;
  header.blockAlign = header.numChannels * header.bitsPerSample / 8;
  memcpy(header.data, "data", 4);
  header.subchunk2Size = dataSize;

  file.write((uint8_t*)&header, sizeof(WavHeader));
}

// Function to update the WAV header with the actual data size
void updateWavHeader(File& file, uint32_t dataSize) {
  uint32_t chunkSize = dataSize + 36;
  uint32_t subchunk2Size = dataSize;

  file.seek(4);  // Seek to chunkSize
  file.write((uint8_t*)&chunkSize, 4);
  file.seek(40);  // Seek to subchunk2Size
  file.write((uint8_t*)&subchunk2Size, 4);
}

void initI2SMicrophone() {
  applog("Initializing I2S bus for waveform");
  i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = I2S_SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,  // Use 32-bit as per sample
    .channel_format = I2S_CHANNEL_FMT_ALL_LEFT,    // SPH0645 select pin is VCC (Left Channel) or test if right channel is silent
    .communication_format = I2S_COMM_FORMAT_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 8,
    .dma_buf_len = 64,
    .use_apll = false,
    .tx_desc_auto_clear = false,
    .fixed_mclk = 0
  };

  i2s_pin_config_t pin_config = {
    .bck_io_num = I2S_BCLK_PIN,
    .ws_io_num = I2S_LRCK_PIN,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = I2S_DOUT_PIN
  };

  if (i2s_driver_install(I2S_NUM_0, &i2s_config, 0, NULL) != ESP_OK) {
    applog("Failed to install I2S driver!");
    while (1)
      ;
  }
  if (i2s_set_pin(I2S_NUM_0, &pin_config) != ESP_OK) {
    applog("Failed to set I2S pins!");
    while (1)
      ;
  }
  // Set clock for 16-bit samples, mono channel
  if (i2s_set_clk(I2S_NUM_0, I2S_SAMPLE_RATE, I2S_BITS_PER_SAMPLE_32BIT, I2S_CHANNEL_MONO) != ESP_OK) {  // Use I2S_CHANNEL_MONO as per existing code
    applog("Failed to set I2S clock!");
    while (1)
      ;
  }
  applog("I2S bus initialized.");

  // --- Initialize Audio Buffering System ---
  updateMinAudioFileSize();
  g_buffer_mutex = xSemaphoreCreateMutex();
  const int buffer_seconds = 3;
  g_audio_buffer.resize(I2S_SAMPLE_RATE * buffer_seconds);
  applog("Audio buffer size: %d for %d seconds", g_audio_buffer.size(), buffer_seconds);
  xTaskCreatePinnedToCore(i2s_read_task, "I2SReaderTask", 4096, NULL, 10, &g_i2s_reader_task_handle, 1);
}

void startRecording() {
  if (!checkFreeSpace()) {
    applog("Not enough free space to start recording. Entering IDLE state.");
    setAppState(IDLE);
    return;
  }

  setAppState(REC, false);
  g_enable_logging = true;
  applog("%ums", millis() - g_boot_time_ms); // ここのログを増やさないで。高速化のために最小限にしてる

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


void finalizeRecording() {
  // Check if the recorded file is too short and delete it if necessary
  File recordedFile = LittleFS.open(g_audio_filename, FILE_READ);
  if (recordedFile) {
    size_t fileSize = recordedFile.size();
    recordedFile.close();

    if (fileSize < MIN_AUDIO_FILE_SIZE_BYTES) {
      applog("File %s is too short (size: %u bytes). Deleting from LittleFS.", g_audio_filename, fileSize);
      if (!LittleFS.remove(g_audio_filename)) {
        applog("Failed to delete short file %s from LittleFS.", g_audio_filename);
      }
      g_audioFileCount = countAudioFiles(); // Update file counts after deletion
    } else {
      applog("Recorded file %s saved (size: %u bytes).", g_audio_filename, fileSize);
    }
  } else {
    applog("ERROR: Could not open recorded file %s to check size.", g_audio_filename);
  }
}

void i2s_read_task(void *pvParameters) {
  const size_t i2s_buffer_samples = 256;
  int32_t* raw_samples = (int32_t*) malloc(i2s_buffer_samples * sizeof(int32_t));
  size_t bytes_read;

  while (true) {
    if (g_is_buffering) {
      i2s_read(I2S_NUM_0, (char*)raw_samples, i2s_buffer_samples * sizeof(int32_t), &bytes_read, portMAX_DELAY);
      
      if (bytes_read > 0) {
        size_t samples_read = bytes_read / sizeof(int32_t);
        int16_t processed_samples[samples_read];

        for (size_t i = 0; i < samples_read; i++) {
          processed_samples[i] = (int16_t)(raw_samples[i] >> 16); // Convert 32-bit to 16-bit
        }
        amplifyAudio(processed_samples, samples_read, AUDIO_GAIN);

        xSemaphoreTake(g_buffer_mutex, portMAX_DELAY);
        for (size_t i = 0; i < samples_read; i++) {
          size_t next_head = (g_buffer_head + 1) % g_audio_buffer.size();
          if (next_head != g_buffer_tail) { // Check for buffer full
            g_audio_buffer[g_buffer_head] = processed_samples[i];
            g_buffer_head = next_head;
          } else {
            // Buffer is full, log an error. Oldest data is overwritten.
            // To prevent this, you might want to increase the buffer size or improve writing speed.
            // For now, we just lose a sample.
          }
        }
        xSemaphoreGive(g_buffer_mutex);
      }
    } else {
      // Wait for the buffering to be enabled
      vTaskDelay(pdMS_TO_TICKS(50));
    }
  }
  free(raw_samples);
}

void updateMinAudioFileSize() {
  // Assuming 16-bit, mono audio, plus 44-byte WAV header
  // bitsPerSample is 16, numChannels is 1
  MIN_AUDIO_FILE_SIZE_BYTES = (size_t)REC_MIN_S * I2S_SAMPLE_RATE * (16 / 8) * 1 + 44;
  applog("MIN_AUDIO_FILE_SIZE_BYTES updated to: %u bytes (for %d seconds)", MIN_AUDIO_FILE_SIZE_BYTES, REC_MIN_S);
}
