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

void writeWavHeaderADPCM(File& file) {
    AdpcmWavHeader header;
    const int samplesPerBlock = 505;
    const int blockAlign = 256;

    memcpy(header.riff, "RIFF", 4);
    header.chunkSize = 0; // Update later
    memcpy(header.wave, "WAVE", 4);
    memcpy(header.fmt, "fmt ", 4);
    header.subchunk1Size = 20;
    header.audioFormat = 0x0011; // IMA ADPCM
    header.numChannels = 1;
    header.sampleRate = I2S_SAMPLE_RATE;
    header.byteRate = (long)(I2S_SAMPLE_RATE * blockAlign + samplesPerBlock - 1) / samplesPerBlock;
    header.blockAlign = blockAlign;
    header.bitsPerSample = 4;
    header.extraDataSize = 2;
    header.samplesPerBlock = samplesPerBlock;
    memcpy(header.fact, "fact", 4);
    header.factChunkSize = 4;
    header.totalSamples = 0; // Update later
    memcpy(header.data, "data", 4);
    header.subchunk2Size = 0; // Update later

    file.write((uint8_t*)&header, sizeof(AdpcmWavHeader));
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

void updateWavHeaderADPCM(File& file, uint32_t dataSize, uint32_t totalSamples) {
    uint32_t chunkSize = dataSize + sizeof(AdpcmWavHeader) - 8;
    file.seek(offsetof(AdpcmWavHeader, chunkSize));
    file.write((uint8_t*)&chunkSize, 4);
    file.seek(offsetof(AdpcmWavHeader, totalSamples));
    file.write((uint8_t*)&totalSamples, 4);
    file.seek(offsetof(AdpcmWavHeader, subchunk2Size));
    file.write((uint8_t*)&dataSize, 4);
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

  if (USE_ADPCM) {
    g_adpcm_buffer_mutex = xSemaphoreCreateMutex();
    g_adpcm_buffer.resize(ADPCM_BUFFER_BLOCKS * ADPCM_BLOCK_SIZE);
    applog("ADPCM buffer size: %d bytes for %d blocks", g_adpcm_buffer.size(), ADPCM_BUFFER_BLOCKS);
  }
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

  // --- Reset buffers and state before anything else ---
  xSemaphoreTake(g_buffer_mutex, portMAX_DELAY);
  g_buffer_head = 0;
  g_buffer_tail = 0;
  g_totalSamplesRecorded = 0;
  xSemaphoreGive(g_buffer_mutex);
  
  g_totalBytesRecorded = 0;
  g_scheduledStopTimeMillis = 0;

  // --- Give immediate user feedback ---
  startVibrationSync(VIBRA_REC_START_MS); // Vibrate immediately
  onboard_led(true);
  updateDisplay("");

  // --- Perform potentially slow file operations ---
  generateFilenameFromRTC(g_audio_filename, sizeof(g_audio_filename));

  g_audioFile = LittleFS.open(g_audio_filename, FILE_WRITE);
  if (!g_audioFile) {
    applog("Failed to open file for writing!");
    onboard_led(false); // Turn off LED to indicate failure
    setAppState(IDLE);  // Revert to IDLE state
    return;
  }

  // --- Prepare file header and state ---
  if (USE_ADPCM) {
    writeWavHeaderADPCM(g_audioFile);
    g_adpcm_buffer_head = 0;
    g_adpcm_buffer_tail = 0;
  } else {
    writeWavHeader(g_audioFile, 0); // Write a placeholder header
  }
  g_audioFileCount = countAudioFiles();

  // --- All clear. Start the recording pipeline. ---
  g_scheduledStopTimeMillis = millis() + (unsigned long)REC_MAX_S * 1000;
  g_is_buffering = true; // NOW, signal I2S task to start buffering

  if (USE_ADPCM) {
    // Higher priority for encoder task
    xTaskCreatePinnedToCore(audio_writer_task, "AudioEncoderTask", 4096, NULL, 6, &g_audio_writer_task_handle, 0);
    // Lower priority for writer task
    xTaskCreatePinnedToCore(file_writer_task, "FileWriterTask", 4096, NULL, 4, &g_file_writer_task_handle, 0);
  } else {
    xTaskCreatePinnedToCore(audio_writer_task, "AudioWriterTask", 4096, NULL, 6, &g_audio_writer_task_handle, 0);
  }
}

void stopRecording() {
  applog("Stopping recording...");
  g_is_buffering = false; // Signal I2S & writer task to stop

  // Wait for the writer/encoder tasks to finish processing their buffers
  if (USE_ADPCM) {
    // For ADPCM, first wait for the encoder task to finish
    if (g_audio_writer_task_handle != NULL) {
        unsigned long wait_start = millis();
        applog("Waiting for encoder task to finish...");
        while(g_audio_writer_task_handle != NULL && (millis() - wait_start < 5000)) {
            vTaskDelay(pdMS_TO_TICKS(50));
        }
        if (g_audio_writer_task_handle != NULL) {
            applog("Encoder task did not terminate, deleting it.");
            vTaskDelete(g_audio_writer_task_handle);
            g_audio_writer_task_handle = NULL;
        }
    }
    // Then, wait for the file writer task to finish
    if (g_file_writer_task_handle != NULL) {
        unsigned long wait_start = millis();
        applog("Waiting for file writer task to finish...");
        while(g_file_writer_task_handle != NULL && (millis() - wait_start < 10000)) {
            vTaskDelay(pdMS_TO_TICKS(50));
        }
        if (g_file_writer_task_handle != NULL) {
            applog("File writer task did not terminate, deleting it.");
            vTaskDelete(g_file_writer_task_handle);
            g_file_writer_task_handle = NULL;
        }
    }
  } else {
    // Original logic for PCM
    if (g_audio_writer_task_handle != NULL) {
        unsigned long wait_start = millis();
        while(g_audio_writer_task_handle != NULL && (millis() - wait_start < 10000)) {
            vTaskDelay(pdMS_TO_TICKS(50));
        }
        if (g_audio_writer_task_handle != NULL) {
            applog("Audio writer task did not terminate, deleting it.");
            vTaskDelete(g_audio_writer_task_handle);
            g_audio_writer_task_handle = NULL;
            if (g_buffer_head != g_buffer_tail) {
                flushAudioBufferToFile();
            }
        }
    }
  }

  onboard_led(false);
  if (g_audioFile) {
    if (USE_ADPCM) {
      updateWavHeaderADPCM(g_audioFile, g_totalBytesRecorded, g_totalSamplesRecorded);
    } else {
      updateWavHeader(g_audioFile, g_totalBytesRecorded);
    }
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

void i2s_read_task(void *pvParameters) { // check_unused:ignore
  const size_t i2s_buffer_samples = 256;
  int32_t* raw_samples = (int32_t*) malloc(i2s_buffer_samples * sizeof(int32_t));
  size_t bytes_read;

  while (true) {
    if (g_is_buffering) {
      i2s_read(I2S_NUM_0, (char*)raw_samples, i2s_buffer_samples * sizeof(int32_t), &bytes_read, portMAX_DELAY);
      
      if (bytes_read > 0) {
        size_t samples_read = bytes_read / sizeof(int32_t);
        
        xSemaphoreTake(g_buffer_mutex, portMAX_DELAY);
        for (size_t i = 0; i < samples_read; i++) {
          // Convert 32-bit to 16-bit
          int32_t val = raw_samples[i] >> 16;
          
          // Amplify audio (inlined from amplifyAudio function)
          val = val * AUDIO_GAIN;
          if (val > 32767) val = 32767;
          if (val < -32768) val = -32768;

          // Write to buffer
          size_t next_head = (g_buffer_head + 1) % g_audio_buffer.size();
          if (next_head != g_buffer_tail) { // Check for buffer full
            g_audio_buffer[g_buffer_head] = (int16_t)val;
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
  if (USE_ADPCM) {
    MIN_AUDIO_FILE_SIZE_BYTES = (size_t)REC_MIN_S * I2S_SAMPLE_RATE / 4 + sizeof(AdpcmWavHeader);
  } else {
    MIN_AUDIO_FILE_SIZE_BYTES = (size_t)REC_MIN_S * I2S_SAMPLE_RATE * (16 / 8) * 1 + sizeof(WavHeader);
  }
  applog("MIN_AUDIO_FILE_SIZE_BYTES updated to: %u bytes (for %d seconds)", MIN_AUDIO_FILE_SIZE_BYTES, REC_MIN_S);
}
