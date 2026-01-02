#include "fastrec_alt.h"
#include <NimBLEDevice.h>
#include <LittleFS.h>
#include <ArduinoJson.h>

#define DEVICE_NAME "fastrec"
#define SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define COMMAND_UUID "beb5483e-36e1-4688-b7f5-ea07361b26aa"
#define RESPONSE_UUID "beb5483e-36e1-4688-b7f5-ea07361b26ab"
#define ACK_UUID "beb5483e-36e1-4688-b7f5-ea07361b26ac"

// Global characteristic pointers to allow access from callbacks
NimBLECharacteristic* pCommandCharacteristic;
NimBLECharacteristic* pResponseCharacteristic;
NimBLECharacteristic* pAckCharacteristic;

SemaphoreHandle_t ackSemaphore = NULL;
SemaphoreHandle_t startTransferSemaphore = NULL;  // 新しく追加するセマフォ

void transferFileChunked() {
  if (!g_start_file_transfer) {
    return;
  }

  // Abort if recording button is pressed before starting
  if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
    applog("Recording button pressed. Aborting file transfer before start.");
    g_start_file_transfer = false;
    return;
  }

  // --- START 信号送信とACK待機 ---
  pResponseCharacteristic->setValue("START");
  pResponseCharacteristic->notify();
  applog("Sent START signal. Waiting for ACK...");

  unsigned long startAckWaitTime = millis();
  bool startAckReceived = false;
  while (millis() - startAckWaitTime < 10000) {  // 10-second total timeout
    if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
      applog("Recording button pressed. Aborting file transfer while waiting for START_ACK.");
      pResponseCharacteristic->setValue("ERROR: Transfer aborted by device");
      pResponseCharacteristic->notify();
      g_start_file_transfer = false;
      return;
    }
    if (xSemaphoreTake(startTransferSemaphore, pdMS_TO_TICKS(50)) == pdTRUE) {
      startAckReceived = true;
      break;
    }
  }

  if (!startAckReceived) {
    applog("START ACK timeout. Aborting file transfer.");
    pResponseCharacteristic->setValue("ERROR: START ACK timeout");
    pResponseCharacteristic->notify();
    g_start_file_transfer = false;
    return;
  }

  applog("Received START ACK. Starting file transfer.");

  // --- END START 信号送信とACK待機 ---

  g_lastActivityTime = millis();  // アクティビティタイマーをリセット

  bool transferAborted = false;

  if (LittleFS.exists(g_file_to_transfer_name.c_str())) {
    File file = LittleFS.open(g_file_to_transfer_name.c_str(), "r");
    if (file) {
      applog("Starting to send file: %s, size: %u", g_file_to_transfer_name.c_str(), file.size());
      const size_t chunkSize = 508; // Adjusted for 4-byte chunk index to fit in 512-byte packet
      uint8_t buffer[chunkSize];
      uint8_t packet[chunkSize + 4]; // Total packet size will be 512 bytes
      size_t bytesRead;
      uint32_t chunkCounter = 0; // Use a 32-bit counter for the chunk index.

      while (true) {
        if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
          applog("Recording button pressed during transfer. Aborting.");
          transferAborted = true;
          break;
        }

        int chunksSentInBurst = 0;
        bool eofReachedInBurst = false;
        int chunk_burst_size_local = g_chunk_burst_size;

        for (int i = 0; i < chunk_burst_size_local; ++i) {
          bytesRead = file.read(buffer, chunkSize);
          if (bytesRead <= 0) {
            eofReachedInBurst = true;
            break;  // End of file
          }
          chunksSentInBurst++;

          // Prepend the chunk number (as a 32-bit little-endian integer)
          packet[0] = (uint8_t)(chunkCounter & 0xFF);
          packet[1] = (uint8_t)((chunkCounter >> 8) & 0xFF);
          packet[2] = (uint8_t)((chunkCounter >> 16) & 0xFF);
          packet[3] = (uint8_t)((chunkCounter >> 24) & 0xFF);
          memcpy(packet + 4, buffer, bytesRead);

          pResponseCharacteristic->setValue(packet, bytesRead + 4);
          while (!pResponseCharacteristic->notify()) {
            delay(10); // Wait a bit for the buffer to clear
          }
          delay(10); // Add a small delay between burst packets to prevent client-side reordering
          chunkCounter++;
        }

        if (chunksSentInBurst == 0) {
          break; // No more data to send
        }

        // If we reached the end of the file in this burst, don't wait for the final ACK.
        // Break out and send EOF. The client will receive the last data and then the EOF.
        if (eofReachedInBurst) {
          break;
        }

        // After sending the burst, wait for a single ACK.
        xSemaphoreTake(ackSemaphore, 0);  // Clear any pending (stale) semaphore
        unsigned long ackWaitStartTime = millis();
        bool ackReceived = false;
        while (millis() - ackWaitStartTime < 2000) {  // 2-second total timeout for burst ACK
          if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
            applog("Recording button pressed during ACK wait. Aborting.");
            transferAborted = true;
            break;
          }
          if (xSemaphoreTake(ackSemaphore, pdMS_TO_TICKS(50)) == pdTRUE) {
            ackReceived = true;
            break;
          }
        }

        if (transferAborted) {
          break;  // Exit main transfer loop
        }

        if (!ackReceived) {
          applog("ACK timeout for chunk burst. Aborting file transfer."); // Removed chunkIndex from log
          transferAborted = true;  // Mark as aborted due to timeout
          break;
        }

        // chunkIndex is not used anymore in the same way, so we remove its update.
        g_lastActivityTime = millis();  // Reset activity timer
      }
      file.close();

      if (!transferAborted) {
        pResponseCharacteristic->setValue("EOF");
        pResponseCharacteristic->notify();
        applog("File sent: %s", g_file_to_transfer_name.c_str());
      } else {
        // For both timeout and manual abort, notify client
        pResponseCharacteristic->setValue("ERROR: Transfer aborted by device");
        pResponseCharacteristic->notify();
        applog("ERROR: Transfer aborted for %s.", g_file_to_transfer_name.c_str());
      }

    } else {
      std::string errorMessage = "ERROR: Failed to open file: ";
      errorMessage += g_file_to_transfer_name;
      pResponseCharacteristic->setValue(errorMessage.c_str());
      pResponseCharacteristic->notify();
      applog("Error: Failed to open file %s", g_file_to_transfer_name.c_str());
    }
  } else {
    std::string errorMessage = "ERROR: File not found: ";
    errorMessage += g_file_to_transfer_name;
    pResponseCharacteristic->setValue(errorMessage.c_str());
    pResponseCharacteristic->notify();
    applog("Error: File not found %s", g_file_to_transfer_name.c_str());
  }
  g_start_file_transfer = false;
}

// --- Command Handlers ---
static void handle_get_file(const std::string& value) {
  std::string file_info = value.substr(std::string("GET:file:").length());
  size_t last_colon_pos = file_info.rfind(':');

  if (last_colon_pos != std::string::npos) {
    // CHUNK_BURST_SIZE is provided
    std::string filename_str = file_info.substr(0, last_colon_pos);
    std::string chunk_burst_size_str = file_info.substr(last_colon_pos + 1);
    g_file_to_transfer_name = "/";
    g_file_to_transfer_name += filename_str;
    g_chunk_burst_size = atoi(chunk_burst_size_str.c_str());
    if (g_chunk_burst_size <= 0) { // Ensure it's a valid number
      g_chunk_burst_size = 8; // Default if invalid
    }
  } else {
    // CHUNK_BURST_SIZE is not provided, use default and entire string as filename
    g_file_to_transfer_name = "/";
    g_file_to_transfer_name += file_info;
    g_chunk_burst_size = 8; // Default value
  }
  g_start_file_transfer = true;
}

static std::string handle_get_setting_ini() {
  if (LittleFS.exists("/setting.ini")) {
    File file = LittleFS.open("/setting.ini", "r");
    if (file) {
      size_t fileSize = file.size();
      if (fileSize > 0) {
        char* buffer = new char[fileSize + 1];
        if (buffer) {
          file.readBytes(buffer, fileSize);
          buffer[fileSize] = '\0';
          std::string responseData = buffer;
          delete[] buffer;
          size_t pos = 0;
          while ((pos = responseData.find("\n", pos)) != std::string::npos) {
            responseData.replace(pos, 1, "\r\n");
            pos += 2;
          }
          return responseData;
        } else {
          return "ERROR: Memory allocation failed";
        }
      } else {
        return "";  // Empty file
      }
      file.close();
    } else {
      return "ERROR: Failed to open setting.ini";
    }
  } else {
    return "ERROR: setting.ini not found";
  }
  return "ERROR: Unknown error in handle_get_setting_ini";  // Should not be reached
}

static std::string handle_get_ls(const std::string& value) {
  std::string ext_from_val = value.substr(std::string("GET:ls:").length());
  if (ext_from_val.empty()) {
    return "ERROR: No extension specified for GET:ls";
  }

  std::string extension = ext_from_val;
  if (!extension.starts_with(".")) {
    extension = "." + extension;
  }

  const int MAX_LS_FILES = 10;
  std::string files[MAX_LS_FILES];
  unsigned long file_sizes[MAX_LS_FILES];  // Array to store file sizes
  int file_count = 0;

  File root = LittleFS.open("/", "r");
  if (!root) {
    return "ERROR: Failed to open root directory";
  }

  File file = root.openNextFile();
  while (file) {
    if (!file.isDirectory()) {
      std::string fileName = file.name();
      if (fileName.ends_with(extension)) {
        if (file_count < MAX_LS_FILES) {
          files[file_count] = fileName;
          file_sizes[file_count] = file.size();
          file_count++;
        } else {
          int max_idx = 0;
          for (int i = 1; i < MAX_LS_FILES; i++) {
            if (files[i] > files[max_idx]) {
              max_idx = i;
            }
          }
          if (fileName < files[max_idx]) {
            files[max_idx] = fileName;
            file_sizes[max_idx] = file.size();
          }
        }
      }
    }
    file.close();  // Close the file after use
    file = root.openNextFile();
  }
  root.close();

  // Sort the collected files and their sizes using a simple bubble sort
  for (int i = 0; i < file_count - 1; i++) {
    for (int j = 0; j < file_count - i - 1; j++) {
      if (files[j] > files[j + 1]) {
        // Swap filenames
        std::string temp_name = files[j];
        files[j] = files[j + 1];
        files[j + 1] = temp_name;

        // Swap corresponding file sizes
        unsigned long temp_size = file_sizes[j];
        file_sizes[j] = file_sizes[j + 1];
        file_sizes[j + 1] = temp_size;
      }
    }
  }

  StaticJsonDocument<1024> doc;  // Increased size to accommodate 10 file entries
  JsonArray fileArray = doc.to<JsonArray>();

  for (int i = 0; i < file_count; i++) {
    JsonObject fileEntry = fileArray.add<JsonObject>();
    fileEntry["name"] = files[i];
    fileEntry["size"] = file_sizes[i];
  }

  std::string jsonResponseStd;
  serializeJson(doc, jsonResponseStd);
  return jsonResponseStd;
}

static std::string handle_get_info() {
  StaticJsonDocument<1024> doc;

  int wav_count = 0;
  int txt_count = 0;
  int ini_count = 0;

  File root = LittleFS.open("/", "r");
  if (root) {
    File file = root.openNextFile();
    while (file) {
      if (!file.isDirectory()) {
        const char* filename = file.name();
        if (strstr(filename, ".wav")) wav_count++;
        else if (strstr(filename, ".txt")) txt_count++;
        else if (strstr(filename, ".ini")) ini_count++;
      }
      file.close();
      file = root.openNextFile();
    }
    root.close();
  } else {
    applog("ERROR: Failed to open root directory for info");
  }

  doc["wav_count"] = wav_count;
  doc["txt_count"] = txt_count;
  doc["ini_count"] = ini_count;

  float batteryLevel = ((g_currentBatteryVoltage - BAT_VOL_MIN) / 1.0f) * 100.0f;
  if (batteryLevel < 0.0f) batteryLevel = 0.0f;
  if (batteryLevel > 100.0f) batteryLevel = 100.0f;
  doc["battery_level"] = batteryLevel;
  doc["battery_voltage"] = g_currentBatteryVoltage;
  doc["app_state"] = appStateStrings[g_currentAppState];

  unsigned long totalBytes = LittleFS.totalBytes();
  unsigned long usedBytes = LittleFS.usedBytes();
  doc["littlefs_total_bytes"] = totalBytes;
  doc["littlefs_used_bytes"] = usedBytes;
  doc["littlefs_usage_percent"] = (totalBytes > 0) ? (int)((float)usedBytes / totalBytes * 100) : 0;
  doc["buf_ovf"] = g_buffer_overflow_count;
  std::string jsonResponseStd;
  serializeJson(doc, jsonResponseStd);
  return jsonResponseStd;
}

static void handle_set_setting_ini(const std::string& value) {
  std::string settingContent = value.substr(std::string("SET:setting_ini:").length());
  std::string responseData;
  File file = LittleFS.open("/setting.ini", "w");
  if (file) {
    file.print(settingContent.c_str());
    file.close();
    responseData = "OK: setting.ini saved. Restarting...";
    pResponseCharacteristic->setValue(responseData.c_str());
    pResponseCharacteristic->notify();
    delay(100);
    ESP.restart();
  } else {
    responseData = "ERROR: Failed to open setting.ini for writing";
    pResponseCharacteristic->setValue(responseData.c_str());
    pResponseCharacteristic->notify();
    applog(responseData.c_str());
  }
}

static std::string handle_del_file(const std::string& value) {
  std::string fileNameToDelete = value.substr(std::string("DEL:file:").length());
  if (fileNameToDelete.length() > 0 && fileNameToDelete[0] != '/') {
    fileNameToDelete = "/" + fileNameToDelete;
  }

  if (LittleFS.exists(fileNameToDelete.c_str())) {
    if (LittleFS.remove(fileNameToDelete.c_str())) {
      applog("Deleted file: %s", fileNameToDelete.c_str());
      g_audioFileCount = countAudioFiles();  // Update global count after successful deletion
      return "OK: File " + fileNameToDelete + " deleted.";
    } else {
      applog("ERROR: Failed to delete file: %s", fileNameToDelete.c_str());
      return "ERROR: Failed to delete file " + fileNameToDelete;
    }
  } else {
    applog("ERROR: File not found: %s", fileNameToDelete.c_str());
    return "ERROR: File " + fileNameToDelete + " not found.";
  }
}

static std::string handle_set_time(const std::string& value) {
  std::string timestamp_str = value.substr(std::string("SET:time:").length());
  if (!timestamp_str.empty()) {
    long long timestamp_ll = atoll(timestamp_str.c_str());
    if (timestamp_ll > MIN_VALID_TIMESTAMP) {  // Basic validation (after 2024-01-01)
      struct timeval tv;
      tv.tv_sec = (time_t)timestamp_ll;  // atollからtime_tへのキャストを明確化
      tv.tv_usec = 0;
      settimeofday(&tv, NULL);

      time_t now;
      struct tm timeinfo;
      char time_buf[64];
      time(&now);
      localtime_r(&now, &timeinfo);
      strftime(time_buf, sizeof(time_buf), "%Y-%m-%d %H:%M:%S", &timeinfo);

      applog("Time set via BLE to: %s", time_buf);
      return "OK: Time set to " + std::string(time_buf);
    } else {
      return "ERROR: Invalid timestamp provided.";
    }
  } else {
    return "ERROR: No timestamp provided.";
  }
}

static void handle_cmd_reset_all() {
  if (!LittleFS.begin(true)) {
    pResponseCharacteristic->setValue("LittleFS Mount Failed");
    pResponseCharacteristic->notify();
    return;
  }

  File root = LittleFS.open("/");
  if (!root) {
    pResponseCharacteristic->setValue("Failed to open root directory");
    pResponseCharacteristic->notify();
    return;
  }

  int deleted_count = 0;
  while (true) {
    File file = root.openNextFile();
    if (!file) break;
    if (file.isDirectory()) {
      file.close();
      continue;
    }
    char filePathBuffer[256];
    const char* fileName = file.name();
    file.close();
    if (fileName[0] != '/') {
      snprintf(filePathBuffer, sizeof(filePathBuffer), "/%s", fileName);
    } else {
      strncpy(filePathBuffer, fileName, sizeof(filePathBuffer) - 1);
      filePathBuffer[sizeof(filePathBuffer) - 1] = '\0';
    }
    if (LittleFS.remove(filePathBuffer)) {
      applog("Deleted file: %s", filePathBuffer);
      deleted_count++;
    } else {
      applog("Failed to delete file: %s", filePathBuffer);
    }
  }
  root.close();

  char response[50];
  sprintf(response, "Deleted %d files.", deleted_count);
  pResponseCharacteristic->setValue(response);
  pResponseCharacteristic->notify();
  delay(100);
  ESP.restart();
}

// --- BLE Callbacks ---
class MyCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {  // check_unused:ignore
    std::string value = pCharacteristic->getValue().c_str();

    if (pCharacteristic->getUUID().toString() == ACK_UUID) {
      if (value == "ACK") {
        xSemaphoreGive(ackSemaphore);
      } else if (value == "START_ACK") {
        xSemaphoreGive(startTransferSemaphore);
      }
      g_lastActivityTime = millis();  // ACK受信もアクティビティ
      return;
    }

    if (value.empty()) return;

    if (pCharacteristic->getUUID().toString() == COMMAND_UUID) {
      applog("BLE Command Received: %s", value.c_str());
      g_lastBleCommand = value; // Store the last received command
      g_lastActivityTime = millis();  // コマンド受信もアクティビティ

      if (g_currentAppState != IDLE) {
        std::string busyMessage = "ERROR: Device is busy (State: " + std::string(appStateStrings[g_currentAppState]) + "). Command rejected.";
        pResponseCharacteristic->setValue(busyMessage.c_str());
        pResponseCharacteristic->notify();
        applog(busyMessage.c_str());
        return;
      }

      // --- Command Dispatcher ---
      if (value.rfind("GET:file:", 0) == 0) {
        handle_get_file(value);
        return;
      }

      std::string responseData = "ERROR: Invalid Command";

      if (value == "GET:setting_ini") {
        responseData = handle_get_setting_ini();
      } else if (value == "GET:info") {
        responseData = handle_get_info();
      } else if (value.rfind("GET:ls:", 0) == 0) {
        responseData = handle_get_ls(value);
      } else if (value.rfind("SET:setting_ini:", 0) == 0) {
        handle_set_setting_ini(value);
        return;  // Function handles response and restart
      } else if (value.rfind("DEL:file:", 0) == 0) {
        responseData = handle_del_file(value);
      } else if (value.rfind("SET:time:", 0) == 0) {
        responseData = handle_set_time(value);
      } else if (value == "CMD:reset_all") {
        handle_cmd_reset_all();
        return;  // Function handles response and restart
      }

      pResponseCharacteristic->setValue(responseData.c_str());
      pResponseCharacteristic->notify();
      applog("Sent notification: %s", responseData.c_str());
    }
  }
};

// Helper function to trim leading/trailing whitespace from a char array
void trim_whitespace(char* str) {
  char* end;

  // Trim leading space
  while (isspace((unsigned char)*str)) str++;

  if (*str == 0)  // All spaces?
    return;

  // Trim trailing space
  end = str + strlen(str) - 1;
  while (end > str && isspace((unsigned char)*end)) end--;

  // Write new null terminator
  *(end + 1) = 0;
}

// --- BLEサーバー開始処理 ---
void start_ble_server() {
  ackSemaphore = xSemaphoreCreateBinary();
  startTransferSemaphore = xSemaphoreCreateBinary();  // 新しく追加するセマフォ
  // startTransferSemaphore = xSemaphoreCreateBinary(); // 新しく追加するセマフォ

  NimBLEDevice::init(DEVICE_NAME);

  // --- Add BLE Security ---
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
  // --- End BLE Security ---

  NimBLEDevice::setDefaultPhy(2, 2);
  pBLEServer = NimBLEDevice::createServer();  // Assign to global pointer
  NimBLEDevice::setMTU(517);                  // Set maximum MTU size

  class MyServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
      applog("Client Connected");
    };

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
      applog("Client Disconnected");
      // Only restart advertising if in a valid state
      if (g_currentAppState == IDLE || g_currentAppState == SETUP) {
        applog("Restarting advertising because state is appropriate.");
        start_ble_advertising();
      } else {
        applog("Not restarting advertising due to current app state: %s", appStateStrings[g_currentAppState]);
      }
    }

    void onPhyUpdate(NimBLEConnInfo& connInfo, uint8_t txPhy, uint8_t rxPhy) override {
      applog("PHY updated for connection %u | TX: %d, RX: %d (1=1M, 2=2M, 3=Coded)",
             connInfo.getConnHandle(), txPhy, rxPhy);
    }

    void onAuthenticationComplete(NimBLEConnInfo& connInfo) override {  // Add this callback
      if (connInfo.isAuthenticated()) {
        applog("Authentication successful! Device is bonded.");
      } else {
        applog("Authentication failed.");  // Removed getFailReason()
      }
    }
  };

  pBLEServer->setCallbacks(new MyServerCallbacks());  // Use global pointer
  NimBLEService* pService = pBLEServer->createService(SERVICE_UUID);

  // New COMMAND_UUID characteristic
  pCommandCharacteristic = pService->createCharacteristic(
    COMMAND_UUID,
    NIMBLE_PROPERTY::WRITE  // Allow client to write commands
  );
  pCommandCharacteristic->setCallbacks(new MyCallbacks());

  // New RESPONSE_UUID characteristic
  pResponseCharacteristic = pService->createCharacteristic(
    RESPONSE_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY  // Allow client to read and receive notifications
  );
  pResponseCharacteristic->setValue("Ready for commands");  // Initial value

  // New ACK_UUID characteristic
  pAckCharacteristic = pService->createCharacteristic(
    ACK_UUID,
    NIMBLE_PROPERTY::WRITE);
  pAckCharacteristic->setCallbacks(new MyCallbacks());

  pService->start();

  // BLEアドバタイズ（広告）の準備
  NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->setName(DEVICE_NAME);          // Explicitly set advertising name
  pAdvertising->addServiceUUID(SERVICE_UUID);  // Re-add service UUID
  pAdvertising->enableScanResponse(true);      // Enable scan response
}

void stop_ble_advertising() {
  if (NimBLEDevice::getAdvertising()->isAdvertising()) {
    applog("Stopping BLE advertising.");
    NimBLEDevice::getAdvertising()->stop();
  }
}

void start_ble_advertising() {
  if (!NimBLEDevice::getAdvertising()->isAdvertising() && isBLEConnected() == false) {
    applog("Starting BLE advertising.");
    NimBLEDevice::getAdvertising()->start();
  }
}

void disconnect_ble_clients() {
  if (pBLEServer != nullptr) {
    uint32_t connectedCount = pBLEServer->getConnectedCount();
    if (connectedCount > 0) {
      applog("Disconnecting %d BLE client(s) due to state change.", connectedCount);
      // disconnect all clients
      auto connections = NimBLEDevice::getServer()->getPeerDevices();
      for (auto& conn : connections) {
        NimBLEDevice::getServer()->disconnect(conn);
      }
    }
  }
}

bool isBLEConnected() {
  if (pBLEServer == nullptr) {
    return false;
  }
  return pBLEServer->getConnectedCount() > 0;
}

bool loadSettingsFromLittleFS() {
  applog("Loading settings from /setting.ini...");

  if (!LittleFS.begin()) {
    applog("LittleFS Mount Failed. Using default settings.");
    return false;
  }

  File configFile = LittleFS.open("/setting.ini", "r");
  if (!configFile) {
    applog("Failed to open /setting.ini. Using default settings.");
    return false;
  }

  char lineBuffer[256];  // Buffer to hold each line from the config file
  while (configFile.available()) {
    int bytesRead = configFile.readBytesUntil('\n', lineBuffer, sizeof(lineBuffer) - 1);
    lineBuffer[bytesRead] = '\0';  // Null-terminate the string

    trim_whitespace(lineBuffer);  // Remove leading/trailing whitespace

    if (strlen(lineBuffer) == 0 || lineBuffer[0] == '#') {
      continue;  // Skip empty lines and comments
    }

    char* separator = strchr(lineBuffer, '=');
    if (separator == nullptr) {
      applog("Invalid line in setting.ini: %s", lineBuffer);
      continue;
    }

    *separator = '\0';  // Null-terminate the key part
    char* key = lineBuffer;
    char* value = separator + 1;

    trim_whitespace(key);
    trim_whitespace(value);

    if (strcmp(key, "DEEP_SLEEP_DELAY_MS") == 0) {
      DEEP_SLEEP_DELAY_MS = atol(value);
      applog("Setting DEEP_SLEEP_DELAY_MS to %lu", DEEP_SLEEP_DELAY_MS);
    } else if (strcmp(key, "BAT_VOL_MIN") == 0) {
      BAT_VOL_MIN = atof(value);
      applog("Setting BAT_VOL_MIN to %f", BAT_VOL_MIN);
    } else if (strcmp(key, "BAT_VOL_MULT") == 0) {
      BAT_VOL_MULT = atof(value);
      applog("Setting BAT_VOL_MULT to %f", BAT_VOL_MULT);
    } else if (strcmp(key, "I2S_SAMPLE_RATE") == 0) {
      I2S_SAMPLE_RATE = atoi(value);
      applog("Setting I2S_SAMPLE_RATE to %d", I2S_SAMPLE_RATE);
    } else if (strcmp(key, "REC_MAX_S") == 0) {
      REC_MAX_S = atoi(value);
      applog("Setting REC_MAX_S to %d", REC_MAX_S);
      MAX_REC_DURATION_MS = REC_MAX_S * 1000;  // Recalculate MAX_RECORDING_DURATION_MS
      applog("Recalculated MAX_REC_DURATION_MS to %lu", MAX_REC_DURATION_MS);
    } else if (strcmp(key, "REC_MIN_S") == 0) {
      REC_MIN_S = atoi(value);
      applog("Setting REC_MIN_S to %d", REC_MIN_S);
      updateMinAudioFileSize();  // Recalculate MIN_AUDIO_FILE_SIZE_BYTES
    } else if (strcmp(key, "AUDIO_GAIN") == 0) {
      AUDIO_GAIN = atof(value);
      applog("Setting AUDIO_GAIN to %f", AUDIO_GAIN);
    } else if (strcmp(key, "VIBRA_STARTUP_MS") == 0) {
      VIBRA_STARTUP_MS = atol(value);
      applog("Setting VIBRA_STARTUP_MS to %lu", VIBRA_STARTUP_MS);
    } else if (strcmp(key, "VIBRA_REC_START_MS") == 0) {
      VIBRA_REC_START_MS = atol(value);
      applog("Setting VIBRA_REC_START_MS to %lu", VIBRA_REC_START_MS);
    } else if (strcmp(key, "VIBRA_REC_STOP_MS") == 0) {
      VIBRA_REC_STOP_MS = atol(value);
      applog("Setting VIBRA_REC_STOP_MS to %lu", VIBRA_REC_STOP_MS);
    } else if (strcmp(key, "VIBRA") == 0) {
      VIBRA = (strcmp(value, "true") == 0);
      applog("Setting VIBRA to %s", VIBRA ? "true" : "false");
    } else if (strcmp(key, "DEEP_SLEEP_CYCLE_MINUTES") == 0) {
      DEEP_SLEEP_CYCLE_MINUTES = atol(value);
      DEEP_SLEEP_CYCLE_MS = DEEP_SLEEP_CYCLE_MINUTES * 60 * 1000;
      applog("Setting DEEP_SLEEP_CYCLE_MINUTES to %lu (which is %lu ms)", DEEP_SLEEP_CYCLE_MINUTES, DEEP_SLEEP_CYCLE_MS);
    } else if (strcmp(key, "USE_ADPCM") == 0) {
      USE_ADPCM = (strcmp(value, "true") == 0);
      applog("Setting USE_ADPCM to %s", USE_ADPCM ? "true" : "false");

    } else {
      applog("Unknown setting in setting.ini: %s", key);
    }
  }
  configFile.close();
  applog("Settings loaded.");
  return true;
}
