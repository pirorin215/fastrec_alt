#include "fastrec_alt.h"
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <WiFi.h>

#define DEVICE_NAME "fastrec"
#define SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define COMMAND_UUID "beb5483e-36e1-4688-b7f5-ea07361b26aa"
#define RESPONSE_UUID "beb5483e-36e1-4688-b7f5-ea07361b26ab"
#define ACK_UUID "beb5483e-36e1-4688-b7f5-ea07361b26ac"

// Global characteristic pointers to allow access from callbacks
BLECharacteristic *pCommandCharacteristic;
BLECharacteristic *pResponseCharacteristic;
BLECharacteristic *pAckCharacteristic;

SemaphoreHandle_t ackSemaphore = NULL;

void handleLogTransfer() {
  if (LittleFS.exists(g_log_filename_to_transfer.c_str())) {
    File file = LittleFS.open(g_log_filename_to_transfer.c_str(), "r");
    if (file) {
      applog("Starting to send file: %s", g_log_filename_to_transfer.c_str());
      const size_t chunkSize = 512;
      uint8_t buffer[chunkSize];
      size_t bytesRead;
      int chunkIndex = 0;

      while ((bytesRead = file.read(buffer, chunkSize)) > 0) {
        pResponseCharacteristic->setValue(buffer, bytesRead);
        pResponseCharacteristic->notify();
        //applog("Sent chunk %d, size: %d. Waiting for ACK...", chunkIndex, bytesRead);

        if (xSemaphoreTake(ackSemaphore, pdMS_TO_TICKS(2000)) == pdTRUE) {
          //applog("ACK received for chunk %d.", chunkIndex);
        } else {
          applog("ACK timeout for chunk %d. Aborting transfer.", chunkIndex);
          file.close();
          g_start_log_transfer = false;
          return;
        }
        chunkIndex++;
      }
      file.close();
      
      pResponseCharacteristic->setValue("EOF");
      pResponseCharacteristic->notify();
      applog("Log file sent: %s", g_log_filename_to_transfer.c_str());
    } else {
      pResponseCharacteristic->setValue("ERROR: Failed to open log file");
      pResponseCharacteristic->notify();
      applog("Error: Failed to open log file %s", g_log_filename_to_transfer.c_str());
    }
  } else {
    pResponseCharacteristic->setValue("ERROR: Log file not found");
    pResponseCharacteristic->notify();
    applog("Error: Log file not found %s", g_log_filename_to_transfer.c_str());
  }
  g_start_log_transfer = false;
}

class MyCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    std::string value = pCharacteristic->getValue().c_str();

    if (pCharacteristic->getUUID().toString() == ACK_UUID) {
      if (value == "ACK") {
        xSemaphoreGive(ackSemaphore);
      }
      return;
    }

    if (value.empty()) return;

    if (pCharacteristic->getUUID().toString() == COMMAND_UUID) {
      applog("BLE Command Received: %s", value.c_str());

      if (value.rfind("GET:log:", 0) == 0) {
        g_log_filename_to_transfer = "/";
        g_log_filename_to_transfer += value.substr(std::string("GET:log:").length());
        g_start_log_transfer = true;
        return;
      }

      std::string responseData = "ERROR: Invalid Command";

      if (value.rfind("GET:", 0) == 0) {
        if (value == "GET:setting_ini") {
          if (LittleFS.exists("/setting.ini")) {
            File file = LittleFS.open("/setting.ini", "r");
            if (file) {
              size_t fileSize = file.size();
              if (fileSize > 0) {
                char* buffer = new char[fileSize + 1];
                if (buffer) {
                  file.readBytes(buffer, fileSize);
                  buffer[fileSize] = '\0';
                  responseData = buffer;
                  delete[] buffer;
                  size_t pos = 0;
                  while ((pos = responseData.find("\n", pos)) != std::string::npos) {
                    responseData.replace(pos, 1, "\r\n");
                    pos += 2;
                  }
                } else {
                  responseData = "ERROR: Memory allocation failed";
                }
              } else {
                responseData = ""; // Empty file
              }
              file.close();
            } else {
              responseData = "ERROR: Failed to open setting.ini";
            }
          } else {
            responseData = "ERROR: setting.ini not found";
          }
        } else if (value == "GET:ls") {
          File root = LittleFS.open("/", "r");
          if (root) {
            responseData = "";
            File file = root.openNextFile();
            while (file) {
              responseData += file.name();
              if (file.isDirectory()) {
                responseData += "/";
              }
              responseData += "\n";
              file = root.openNextFile();
            }
            root.close();
          } else {
            responseData = "ERROR: Failed to open root directory";
          }
        } else if (value == "GET:info") {
          StaticJsonDocument<1024> doc;
          std::string littlefs_ls_std = "";
          File root = LittleFS.open("/", "r");
          if (root) {
            File file = root.openNextFile();
            while (file) {
              littlefs_ls_std += file.name();
              if (file.isDirectory()) {
                littlefs_ls_std += "/";
              }
              littlefs_ls_std += "\n";
              file = root.openNextFile();
            }
            root.close();
          } else {
            littlefs_ls_std = "ERROR: Failed to open root directory";
          }
          doc["ls"] = littlefs_ls_std;
          float batteryLevel = ((g_currentBatteryVoltage - BAT_VOL_MIN) / 1.0f) * 100.0f;
          if (batteryLevel < 0.0f) batteryLevel = 0.0f;
          if (batteryLevel > 100.0f) batteryLevel = 100.0f;
          doc["battery_level"] = batteryLevel;
          doc["battery_voltage"] = g_currentBatteryVoltage;
          doc["app_state"] = appStateStrings[g_currentAppState];
          doc["wifi_status"] = isWiFiConnected() ? "Connected" : "Disconnected";
          if (isWiFiConnected()) {
            if (g_connectedSSIDIndex != -1 && g_connectedSSIDIndex < g_num_wifi_aps) {
              doc["connected_ssid"] = g_wifi_ssids[g_connectedSSIDIndex];
            } else {
              doc["connected_ssid"] = "N/A";
            }
            doc["wifi_rssi"] = WiFi.RSSI();
          } else {
            doc["connected_ssid"] = "N/A";
            doc["wifi_rssi"] = 0;
          }
          unsigned long totalBytes = LittleFS.totalBytes();
          unsigned long usedBytes = LittleFS.usedBytes();
          doc["littlefs_total_bytes"] = totalBytes;
          doc["littlefs_used_bytes"] = usedBytes;
          doc["littlefs_usage_percent"] = (totalBytes > 0) ? (int)((float)usedBytes / totalBytes * 100) : 0;
          std::string jsonResponseStd;
          serializeJson(doc, jsonResponseStd);
          responseData = jsonResponseStd;
        }
      } else if (value.rfind("SET:setting_ini:", 0) == 0) {
        std::string settingContent = value.substr(std::string("SET:setting_ini:").length());
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
        }
      } else if (value.rfind("SET:REC_MIN_S:", 0) == 0) {
        int newMinRecDuration = atoi(value.substr(std::string("SET:REC_MIN_S:").length()).c_str());
        if (newMinRecDuration > 0) {
          REC_MIN_S = newMinRecDuration;
          updateMinAudioFileSize();
          responseData = "OK: REC_MIN_S set to " + std::to_string(REC_MIN_S);
        } else {
          responseData = "ERROR: Invalid REC_MIN_S value";
        }
      } else if (value.rfind("CMD:wipe_all", 0) == 0) {
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
          if (!file) {
            break; // No more files
          }

          if (file.isDirectory()) {
            file.close();
            continue;
          }

          char filePathBuffer[256]; // Use a char array instead of String
          const char* fileName = file.name();
          file.close(); // Close file before deleting

          if (fileName[0] != '/') {
            // Prepend "/" if not already present
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
        responseData = response;
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
    while(isspace((unsigned char)*str)) str++;

    if(*str == 0)  // All spaces?
        return;

    // Trim trailing space
    end = str + strlen(str) - 1;
    while(end > str && isspace((unsigned char)*end)) end--;

    // Write new null terminator
    *(end+1) = 0;
}

// --- BLEサーバー開始処理 ---
void start_ble_server() {
  ackSemaphore = xSemaphoreCreateBinary();

  BLEDevice::init(DEVICE_NAME);
  pBLEServer = BLEDevice::createServer(); // Assign to global pointer
  BLEDevice::setMTU(517); // Set maximum MTU size

  class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      applog("Client Connected");
    };

    void onDisconnect(BLEServer* pServer) {
      applog("Client Disconnected - Restarting Advertising");
      BLEAdvertising *pAdvertising = pServer->getAdvertising(); // Use the pServer argument
      pAdvertising->start();
    }
  };

  pBLEServer->setCallbacks(new MyServerCallbacks()); // Use global pointer
  BLEService *pService = pBLEServer->createService(SERVICE_UUID);

  // New COMMAND_UUID characteristic
  pCommandCharacteristic = pService->createCharacteristic(
      COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE // Allow client to write commands
  );
  pCommandCharacteristic->setCallbacks(new MyCallbacks());

  // New RESPONSE_UUID characteristic
  pResponseCharacteristic = pService->createCharacteristic(
      RESPONSE_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY // Allow client to read and receive notifications
  );
  pResponseCharacteristic->setValue("Ready for commands"); // Initial value

  // New ACK_UUID characteristic
  pAckCharacteristic = pService->createCharacteristic(
      ACK_UUID,
      BLECharacteristic::PROPERTY_WRITE
  );
  pAckCharacteristic->setCallbacks(new MyCallbacks());

  pService->start();

  // BLEアドバタイズ（広告）の開始
  BLEAdvertising *pAdvertising = pBLEServer->getAdvertising(); // Use global pBLEServer
  pAdvertising->start();
}

void loadSettingsFromLittleFS() {
  applog("Loading settings from /setting.ini...");

  // Initialize global WiFi AP arrays
  for (int i = 0; i < WIFI_MAX_APS; ++i) {
    g_wifi_ssids[i][0] = '\0';
    g_wifi_passwords[i][0] = '\0';
  }
  g_num_wifi_aps = 0; // Reset count before loading

  if (!LittleFS.begin()) {
    applog("LittleFS Mount Failed. Using default settings.");
    return;
  }

  File configFile = LittleFS.open("/setting.ini", "r");
  if (!configFile) {
    applog("Failed to open /setting.ini. Using default settings.");
    return;
  }

  char lineBuffer[256]; // Buffer to hold each line from the config file
  while (configFile.available()) {
    int bytesRead = configFile.readBytesUntil('\n', lineBuffer, sizeof(lineBuffer) - 1);
    lineBuffer[bytesRead] = '\0'; // Null-terminate the string

    trim_whitespace(lineBuffer); // Remove leading/trailing whitespace

    if (strlen(lineBuffer) == 0 || lineBuffer[0] == '#') {
      continue; // Skip empty lines and comments
    }

    char* separator = strchr(lineBuffer, '=');
    if (separator == nullptr) {
      applog("Invalid line in setting.ini: %s", lineBuffer);
      continue;
    }

    *separator = '\0'; // Null-terminate the key part
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
      MAX_REC_DURATION_MS = REC_MAX_S * 1000; // Recalculate MAX_RECORDING_DURATION_MS
      applog("Recalculated MAX_REC_DURATION_MS to %lu", MAX_REC_DURATION_MS);
    } else if (strcmp(key, "REC_MIN_S") == 0) {
      REC_MIN_S = atoi(value);
      applog("Setting REC_MIN_S to %d", REC_MIN_S);
      updateMinAudioFileSize(); // Recalculate MIN_AUDIO_FILE_SIZE_BYTES
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
    } else if (strcmp(key, "LOG_AT_BOOT") == 0) {
      g_is_log_at_boot = (strcmp(value, "true") == 0);
      applog("Setting LOG_AT_BOOT to %s", g_is_log_at_boot ? "true" : "false");
    } else if (strcmp(key, "HS_HOST") == 0) {
      HS_HOST = strdup(value);
      applog("Setting HS_HOST to %s", HS_HOST);
    } else if (strcmp(key, "HS_PORT") == 0) {
      HS_PORT = atoi(value);
      applog("Setting HS_PORT to %d", HS_PORT);
    } else if (strcmp(key, "HS_PATH") == 0) {
      HS_PATH = strdup(value);
      applog("Setting HS_PATH to %s", HS_PATH);
    } else if (strcmp(key, "HS_USER") == 0) {
      HS_USER = strdup(value);
      applog("Setting HS_USER to %s", HS_USER);
    } else if (strcmp(key, "HS_PASS") == 0) {
      HS_PASS = strdup(value);
      applog("Setting HS_PASS to %s", HS_PASS);
    } else if (strncmp(key, "W_SSID_", strlen("W_SSID_")) == 0) {
      int apIndex = atoi(key + strlen("W_SSID_"));
      if (apIndex >= 0 && apIndex < WIFI_MAX_APS) {
        strncpy(g_wifi_ssids[apIndex], value, sizeof(g_wifi_ssids[apIndex]) - 1);
        g_wifi_ssids[apIndex][sizeof(g_wifi_ssids[apIndex]) - 1] = '\0'; // Ensure null termination
        applog("Setting W_SSID_%d to %s", apIndex, g_wifi_ssids[apIndex]);
      }
    } else if (strncmp(key, "W_PASS_", strlen("W_PASS_")) == 0) {
      int apIndex = atoi(key + strlen("W_PASS_"));
      if (apIndex >= 0 && apIndex < WIFI_MAX_APS) {
        strncpy(g_wifi_passwords[apIndex], value, sizeof(g_wifi_passwords[apIndex]) - 1);
        g_wifi_passwords[apIndex][sizeof(g_wifi_passwords[apIndex]) - 1] = '\0'; // Ensure null termination
        applog("Setting W_PASS_%d to %s", apIndex, g_wifi_passwords[apIndex]);
      }
    } else {
      applog("Unknown setting in setting.ini: %s", key);
    }
  }

  // After parsing all lines, count the number of configured WiFi APs
  g_num_wifi_aps = 0;
  for (int i = 0; i < WIFI_MAX_APS; ++i) {
    // Check if both SSID and password are set for this index
    if (strlen(g_wifi_ssids[i]) > 0 && strlen(g_wifi_passwords[i]) > 0) {
      g_num_wifi_aps++;
    } else {
      // If an AP is missing, assume subsequent ones are also missing
      break;
    }
  }
  applog("Configured %d WiFi APs.", g_num_wifi_aps);

  configFile.close();
  applog("Settings loaded.");
}
