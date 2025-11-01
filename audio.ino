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
  Serial.println("Initializing I2S bus for waveform");
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
    Serial.println("Failed to install I2S driver!");
    while (1)
      ;
  }
  if (i2s_set_pin(I2S_NUM_0, &pin_config) != ESP_OK) {
    Serial.println("Failed to set I2S pins!");
    while (1)
      ;
  }
  // Set clock for 16-bit samples, mono channel
  if (i2s_set_clk(I2S_NUM_0, I2S_SAMPLE_RATE, I2S_BITS_PER_SAMPLE_32BIT, I2S_CHANNEL_MONO) != ESP_OK) {  // Use I2S_CHANNEL_MONO as per existing code
    Serial.println("Failed to set I2S clock!");
    while (1)
      ;
  }
  Serial.println("I2S bus initialized.");
}


void startRecording() {
  g_startButtonPressedISR = false;
  Serial.println("Starting recording.");

  if (!checkFreeSpace()) {
    Serial.println("Not enough free space to start recording. Entering IDLE state.");
    setAppState(IDLE);  // Go to IDLE state if not enough space
    return;
  }
  
  setAppState(REC, false);
  onboard_led(true);
  startVibration(VIBRA_REC_START_MS);  // Vibrate on record start

  float usagePercentage = getLittleFSUsagePercentage();
  updateDisplay("");

  generateFilenameFromRTC(g_audio_filename, sizeof(g_audio_filename));  // Generate filename before recording
  Serial.printf("Opening file %s for writing...\r\n", g_audio_filename);

  g_audioFile = LittleFS.open(g_audio_filename, FILE_WRITE);
  if (!g_audioFile) {
    Serial.println("Failed to open file for writing!");
    setAppState(DSLEEP);  // Cannot record, go to deep sleep
    return;
  }

  writeWavHeader(g_audioFile, 0);  // Write a placeholder header
  g_audioFileCount = countAudioFiles();            // Update file counts after creating a new file

  g_scheduledStopTimeMillis = millis() + MAX_REC_DURATION_MS;
  g_totalBytesRecorded = 0;
  Serial.println("Recording audio data...");
}

void stopRecording() {
  Serial.println("Stopping recording.");
  startVibrationSync(VIBRA_REC_STOP_MS);
  onboard_led(false);
  updateWavHeader(g_audioFile, g_totalBytesRecorded);
  g_audioFile.close();
  Serial.printf("Debug: Filename in stopRecording: %s\r\n", g_audio_filename);  // Debug print
  float usagePercentage = getLittleFSUsagePercentage();
  updateDisplay("");
  setAppState(IDLE);
  startVibrationSync(VIBRA_REC_STOP_MS);
}

void addRecording() {
  size_t bytes_read = 0;
  int32_t raw_samples[I2S_BUFFER_SIZE / sizeof(int32_t)];  // Read as 32-bit from I2S

  i2s_read(I2S_NUM_0, (char*)raw_samples, I2S_BUFFER_SIZE, &bytes_read, portMAX_DELAY);

  if (bytes_read > 0) {
    for (size_t i = 0; i < bytes_read / sizeof(int32_t); i++) {
      g_i2s_read_buffer[i] = (int16_t)(raw_samples[i] >> 16);  // Convert 32-bit to 16-bit
    }
    // Amplify audio samples
    amplifyAudio(g_i2s_read_buffer, bytes_read / sizeof(int32_t), AUDIO_GAIN);
    g_audioFile.write((uint8_t*)g_i2s_read_buffer, bytes_read / 2);
    g_totalBytesRecorded += (bytes_read / 2);
  }
}
