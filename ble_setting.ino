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

// Global characteristic pointers to allow access from callbacks
BLECharacteristic *pCommandCharacteristic;
BLECharacteristic *pResponseCharacteristic;

class MyCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    std::string value = pCharacteristic->getValue().c_str();
    if (value.empty()) return;

    // Handle COMMAND_UUID writes (new functionality)
    else if (pCharacteristic->getUUID().toString() == COMMAND_UUID) {
      applog("COMMAND_UUID received: %s\n", value.c_str());

      // --- Command Parsing and Data Retrieval (Example) ---
      // In a real application, you would parse 'value' (e.g., "GET:sensor_data:0")
      // to extract category and index, then retrieve actual data.
      char responseData[512] = "ERROR: Invalid Command"; // Default error response

      if (value.rfind("GET:", 0) == 0) { // Check if command starts with "GET:"
        // Handle GET:setting_ini as a special case
        if (value == "GET:setting_ini") {
          if (LittleFS.exists("/setting.ini")) {
            File file = LittleFS.open("/setting.ini", "r");
            if (file) {
              size_t fileSize = file.size();
              if (fileSize < sizeof(responseData) - 1) {
                int len = file.readBytes(responseData, fileSize);
                responseData[len] = '\0';
                
                // Replace \n with \r\n in-place
                char tempBuf[512];
                int j = 0;
                for (int i = 0; i < len && j < sizeof(tempBuf) - 2; i++) {
                  if (responseData[i] == '\n') {
                    tempBuf[j++] = '\r';
                    tempBuf[j++] = '\n';
                  } else {
                    tempBuf[j++] = responseData[i];
                  }
                }
                tempBuf[j] = '\0';
                strcpy(responseData, tempBuf);

              } else {
                strcpy(responseData, "ERROR: setting.ini is too large");
              }
              file.close();
            } else {
              strcpy(responseData, "ERROR: Failed to open setting.ini");
            }
          } else {
            strcpy(responseData, "ERROR: setting.ini not found");
          }
                  } else if (value == "GET:ls") { // Handle GET:ls command
                    File root = LittleFS.open("/", "r");
                    if (root) {
                      responseData[0] = '\0';
                      int pos = 0;
                      File file = root.openNextFile();
                      while (file) {
                        const char* name = file.name();
                        int name_len = strlen(name);
                        if (pos + name_len + 2 < sizeof(responseData)) {
                          strcpy(responseData + pos, name);
                          pos += name_len;
                          if (file.isDirectory()) {
                            responseData[pos++] = '/';
                          }
                          responseData[pos++] = '\n';
                          responseData[pos] = '\0';
                        }
                        file = root.openNextFile();
                      }
                      root.close();
                    } else {
                      strcpy(responseData, "ERROR: Failed to open LittleFS root");
                    }
                  } else if (value == "GET:info") { // Handle GET:info command
                    StaticJsonDocument<1024> doc; // Increased size to accommodate all info
        
                    // 1. ディレクトリ一覧 (Directory listing)
                    char littlefs_ls_char[256] = "";
                    int pos = 0;
                    File root = LittleFS.open("/", "r");
                    if (root) {
                      File file = root.openNextFile();
                      while (file) {
                        const char* name = file.name();
                        int name_len = strlen(name);
                        if (pos + name_len + 2 < sizeof(littlefs_ls_char)) {
                            strcpy(littlefs_ls_char + pos, name);
                            pos += name_len;
                            if (file.isDirectory()) {
                                littlefs_ls_char[pos++] = '/';
                            }
                            littlefs_ls_char[pos++] = '\n';
                            littlefs_ls_char[pos] = '\0';
                        }
                        file = root.openNextFile();
                      }
                      root.close();
                    } else {
                      strcpy(littlefs_ls_char, "ERROR: Failed to open LittleFS root");
                    }
                    doc["ls"] = littlefs_ls_char;
        
                    // 2. バッテリーレベル (Battery level)
                    float batteryLevel = ((g_currentBatteryVoltage - BAT_VOL_MIN) / 1.0f) * 100.0f;
                    if (batteryLevel < 0.0f) batteryLevel = 0.0f;
                    if (batteryLevel > 100.0f) batteryLevel = 100.0f;                    doc["battery_level"] = batteryLevel;
        
                    // 3. バッテリー電圧 (Battery voltage)
                    doc["battery_voltage"] = g_currentBatteryVoltage;
        
                    // 4. アプリ状態 (App state)
                    doc["app_state"] = appStateStrings[g_currentAppState];
        
                    // 5. WiFi接続状態 (WiFi connection status)
                    doc["wifi_status"] = isWiFiConnected() ? "Connected" : "Disconnected";
                    if (isWiFiConnected()) {
                      if (g_connectedSSIDIndex != -1 && g_connectedSSIDIndex < g_num_wifi_aps) {
                        doc["connected_ssid"] = g_wifi_ssids[g_connectedSSIDIndex];
                      } else {
                        doc["connected_ssid"] = "N/A"; // Should not happen if isWiFiConnected() is true, but as a safeguard
                      }
                      doc["wifi_rssi"] = WiFi.RSSI();
                    } else {
                      doc["connected_ssid"] = "N/A";
                      doc["wifi_rssi"] = 0; // Or some other indicator for no signal
                    }
        
                    // 6. LittleFS使用率 (LittleFS usage)
                    unsigned long totalBytes = LittleFS.totalBytes();
                    unsigned long usedBytes = LittleFS.usedBytes();
                    doc["littlefs_total_bytes"] = totalBytes;
                    doc["littlefs_used_bytes"] = usedBytes;
                    doc["littlefs_usage_percent"] = (totalBytes > 0) ? (int)((float)usedBytes / totalBytes * 100) : 0;
        
                    // Serialize JSON to string
                    serializeJson(doc, responseData, sizeof(responseData));
        
                  } else if (value.rfind("GET:log_size:", 0) == 0) {
                    const char* filename = value.c_str() + strlen("GET:log_size:");
                    if (LittleFS.exists(filename)) {
                      File file = LittleFS.open(filename, "r");
                      if (file) {
                        snprintf(responseData, sizeof(responseData), "LOG_SIZE:%u", file.size());
                        file.close();
                      } else {
                        strcpy(responseData, "ERROR: Failed to open file");
                      }
                    } else {
                      strcpy(responseData, "ERROR: File not found");
                    }
                  } else if (value.rfind("GET:log:", 0) == 0) {
                    // Format: GET:log:<filename>:<offset>
                    std::string val_str = value;
                    size_t first_colon = val_str.find(':');
                    size_t second_colon = val_str.find(':', first_colon + 1);
                    size_t third_colon = val_str.find(':', second_colon + 1);

                    if (third_colon != std::string::npos) {
                        std::string filename = val_str.substr(second_colon + 1, third_colon - second_colon - 1);
                        unsigned long offset = std::stoul(val_str.substr(third_colon + 1));

                        if (LittleFS.exists(filename.c_str())) {
                            File file = LittleFS.open(filename.c_str(), "r");
                            if (file) {
                                file.seek(offset);
                                const int chunkSize = 480;
                                uint8_t buffer[chunkSize];
                                size_t bytesRead = file.read(buffer, sizeof(buffer));
                                if (bytesRead > 0) {
                                    pResponseCharacteristic->setValue(buffer, bytesRead);
                                    pResponseCharacteristic->notify();
                                }
                                file.close();
                            }
                        }
                    }
                    responseData[0] = '\0'; // Command handles its own response

                  } else {
                    strcpy(responseData, "ERROR: Invalid GET command format"); // Or a more specific error
                  }
      } else if (value.rfind("SET:setting_ini:", 0) == 0) { // Handle SET:setting_ini
        std::string settingContent = value.substr(std::string("SET:setting_ini:").length());
        File file = LittleFS.open("/setting.ini", "w");
        if (file) {
          file.print(settingContent.c_str());
          file.close();
          strcpy(responseData, "OK: setting.ini saved. Restarting...");
          ESP.restart(); // Restart to apply new settings
        } else {
          strcpy(responseData, "ERROR: Failed to open setting.ini for writing");
        }
      } else if (value.rfind("SET:REC_MIN_S:", 0) == 0) {
        int newMinRecDuration = atoi(value.substr(std::string("SET:REC_MIN_S:").length()).c_str());
        if (newMinRecDuration > 0) {
          REC_MIN_S = newMinRecDuration;
          updateMinAudioFileSize();
          snprintf(responseData, sizeof(responseData), "OK: REC_MIN_S set to %d", REC_MIN_S);
        } else {
          strcpy(responseData, "ERROR: Invalid REC_MIN_S value");
        }
      } else if (strcmp(value.c_str(), "CMD:format_fs:format_now") == 0) {
        applog("Formatting LittleFS...");
        if (LittleFS.format()) {
          strcpy(responseData, "OK: LittleFS formatted. Restarting...");
          pResponseCharacteristic->setValue(responseData);
          pResponseCharacteristic->notify();
          delay(100); // Allow time for BLE response to send
          ESP.restart();
        } else {
          strcpy(responseData, "ERROR: Failed to format LittleFS");
        }
      }
      // --- End of Command Parsing and Data Retrieval ---

      // Send response via notification, only if there is something to send
      if (pResponseCharacteristic != nullptr && strlen(responseData) > 0) {
        pResponseCharacteristic->setValue(responseData);
        pResponseCharacteristic->notify(); // Send notification to client
        applog("Sent notification: %s\n", responseData);
      } else if (pResponseCharacteristic == nullptr) {
        applog("Error: pResponseCharacteristic is null!\n");
      }
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
  BLEDevice::init(DEVICE_NAME);
  pBLEServer = BLEDevice::createServer(); // Assign to global pointer
  BLEDevice::setMTU(517); // Set maximum MTU size

  class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      applog("Client Connected\n");
    };

    void onDisconnect(BLEServer* pServer) {
      applog("Client Disconnected - Restarting Advertising\n");
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

  pService->start();

  // BLEアドバタイズ（広告）の開始
  BLEAdvertising *pAdvertising = pBLEServer->getAdvertising(); // Use global pBLEServer
  pAdvertising->start();
}

void createDefaultSettingsFile() {
  applog("Creating default /setting.ini file...\n");
  File configFile = LittleFS.open("/setting.ini", "w");
  if (!configFile) {
    applog("Failed to create /setting.ini\n");
    return;
  }

  configFile.println("DEEP_SLEEP_DELAY_MS=15000");
  configFile.println("BAT_VOL_MIN=3.0");
  configFile.println("BAT_VOL_MULT=2.1");
  configFile.println("I2S_SAMPLE_RATE=8000");
  configFile.println("REC_MAX_S=20");
  configFile.println("REC_MIN_S=1");
  configFile.println("AUDIO_GAIN=8.0");
  configFile.println("VIBRA_STARTUP_MS=600");
  configFile.println("VIBRA_REC_START_MS=600");
  configFile.println("VIBRA_REC_STOP_MS=600");
  configFile.println("HS_HOST=yoshi1108.ddns.net");
  configFile.println("HS_PORT=55443");
  configFile.println("HS_PATH=/fastrec/upload");
  configFile.println("HS_USER=fastrec");
  configFile.println("HS_PASS=Fjfj1108");
  configFile.println("W_SSID_0=Your_SSID");
  configFile.println("W_PASS_0=Your_Password");

  configFile.close();
  applog("Default /setting.ini created successfully.\n");
}

void loadSettingsFromLittleFS() {
  applog("Loading settings from /setting.ini...\n");

  // Initialize global WiFi AP arrays
  for (int i = 0; i < WIFI_MAX_APS; ++i) {
    g_wifi_ssids[i][0] = '\0';
    g_wifi_passwords[i][0] = '\0';
  }
  g_num_wifi_aps = 0; // Reset count before loading

  if (!LittleFS.begin()) {
    applog("LittleFS Mount Failed. Using default settings.\n");
    return;
  }

  File configFile = LittleFS.open("/setting.ini", "r");
  if (!configFile) {
    applog("Failed to open /setting.ini. Creating default file and restarting.\n");
    createDefaultSettingsFile();
    ESP.restart();
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
      applog("Invalid line in setting.ini: %s\r\n", lineBuffer);
      continue;
    }

    *separator = '\0'; // Null-terminate the key part
    char* key = lineBuffer;
    char* value = separator + 1;

    trim_whitespace(key);
    trim_whitespace(value);

    if (strcmp(key, "DEEP_SLEEP_DELAY_MS") == 0) {
      DEEP_SLEEP_DELAY_MS = atol(value);
      applog("Setting DEEP_SLEEP_DELAY_MS to %lu\r\n", DEEP_SLEEP_DELAY_MS);
    } else if (strcmp(key, "BAT_VOL_MIN") == 0) {
      BAT_VOL_MIN = atof(value);
      applog("Setting BAT_VOL_MIN to %f\r\n", BAT_VOL_MIN);
    } else if (strcmp(key, "BAT_VOL_MULT") == 0) {
      BAT_VOL_MULT = atof(value);
      applog("Setting BAT_VOL_MULT to %f\r\n", BAT_VOL_MULT);
    } else if (strcmp(key, "I2S_SAMPLE_RATE") == 0) {
      I2S_SAMPLE_RATE = atoi(value);
      applog("Setting I2S_SAMPLE_RATE to %d\r\n", I2S_SAMPLE_RATE);
    } else if (strcmp(key, "REC_MAX_S") == 0) {
      REC_MAX_S = atoi(value);
      applog("Setting REC_MAX_S to %d\r\n", REC_MAX_S);
      MAX_REC_DURATION_MS = REC_MAX_S * 1000; // Recalculate MAX_RECORDING_DURATION_MS
      applog("Recalculated MAX_REC_DURATION_MS to %lu\n", MAX_REC_DURATION_MS);
    } else if (strcmp(key, "REC_MIN_S") == 0) {
      REC_MIN_S = atoi(value);
      applog("Setting REC_MIN_S to %d\r\n", REC_MIN_S);
      updateMinAudioFileSize(); // Recalculate MIN_AUDIO_FILE_SIZE_BYTES
    } else if (strcmp(key, "AUDIO_GAIN") == 0) {
      AUDIO_GAIN = atof(value);
      applog("Setting AUDIO_GAIN to %f\r\n", AUDIO_GAIN);
    } else if (strcmp(key, "VIBRA_STARTUP_MS") == 0) {
      VIBRA_STARTUP_MS = atol(value);
      applog("Setting VIBRA_STARTUP_MS to %lu\r\n", VIBRA_STARTUP_MS);
    } else if (strcmp(key, "VIBRA_REC_START_MS") == 0) {
      VIBRA_REC_START_MS = atol(value);
      applog("Setting VIBRA_REC_START_MS to %lu\r\n", VIBRA_REC_START_MS);
    } else if (strcmp(key, "VIBRA_REC_STOP_MS") == 0) {
      VIBRA_REC_STOP_MS = atol(value);
      applog("Setting VIBRA_REC_STOP_MS to %lu\r\n", VIBRA_REC_STOP_MS);
    } else if (strcmp(key, "HS_HOST") == 0) {
      HS_HOST = strdup(value);
      applog("Setting HS_HOST to %s\r\n", HS_HOST);
    } else if (strcmp(key, "HS_PORT") == 0) {
      HS_PORT = atoi(value);
      applog("Setting HS_PORT to %d\r\n", HS_PORT);
    } else if (strcmp(key, "HS_PATH") == 0) {
      HS_PATH = strdup(value);
      applog("Setting HS_PATH to %s\r\n", HS_PATH);
    } else if (strcmp(key, "HS_USER") == 0) {
      HS_USER = strdup(value);
      applog("Setting HS_USER to %s\r\n", HS_USER);
    } else if (strcmp(key, "HS_PASS") == 0) {
      HS_PASS = strdup(value);
      applog("Setting HS_PASS to %s\r\n", HS_PASS);
    } else if (strncmp(key, "W_SSID_", strlen("W_SSID_")) == 0) {
      int apIndex = atoi(key + strlen("W_SSID_"));
      if (apIndex >= 0 && apIndex < WIFI_MAX_APS) {
        strncpy(g_wifi_ssids[apIndex], value, sizeof(g_wifi_ssids[apIndex]) - 1);
        g_wifi_ssids[apIndex][sizeof(g_wifi_ssids[apIndex]) - 1] = '\0'; // Ensure null termination
        applog("Setting W_SSID_%d to %s\r\n", apIndex, g_wifi_ssids[apIndex]);
      }
    } else if (strncmp(key, "W_PASS_", strlen("W_PASS_")) == 0) {
      int apIndex = atoi(key + strlen("W_PASS_"));
      if (apIndex >= 0 && apIndex < WIFI_MAX_APS) {
        strncpy(g_wifi_passwords[apIndex], value, sizeof(g_wifi_passwords[apIndex]) - 1);
        g_wifi_passwords[apIndex][sizeof(g_wifi_passwords[apIndex]) - 1] = '\0'; // Ensure null termination
        applog("Setting W_PASS_%d to %s\r\n", apIndex, g_wifi_passwords[apIndex]);
      }
    } else {
      applog("Unknown setting in setting.ini: %s\r\n", key);
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
  applog("Configured %d WiFi APs.\r\n", g_num_wifi_aps);

  configFile.close();
  applog("Settings loaded.\n");
}
