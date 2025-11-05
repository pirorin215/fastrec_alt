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

bool shouldRestart = false;



class CommandCharacteristicCallbacks : public BLECharacteristicCallbacks {

  void onWrite(BLECharacteristic *pCharacteristic) {
    String value = pCharacteristic->getValue();
    String command = String(value.c_str());
    String response = "";

    if (command.startsWith("GET:setting_ini")) {
      File file = LittleFS.open("/setting.ini", "r");
      if (file) {
        response = file.readString();
        file.close();
      }
      response += "[EOM]";
    } else if (command.startsWith("SET:setting_ini:")) {
      String content = command.substring(16);
      File file = LittleFS.open("/setting.ini", "w");
      if (file) {
        file.print(content);
        file.close();
        response = "OK[EOM]";
        shouldRestart = true; 
      }
    } else if (command.startsWith("GET:info")) {
      response = getSystemInfoJson();
      response += "[EOM]";
    } else if (command.startsWith("GET:log:")) {
      String filename = "/" + command.substring(8);
      applog("Received GET:log: command for file: %s\n", filename.c_str());

      if (LittleFS.exists(filename)) {
        File file = LittleFS.open(filename, "r");
        if (file) {
            applog("Sending file %s...\n", filename.c_str());
            const size_t chunkSize = 512;
            uint8_t buffer[chunkSize];
            while (file.available()) {
                size_t bytesRead = file.read(buffer, chunkSize);
                if (bytesRead > 0) {
                    pResponseCharacteristic->setValue(buffer, bytesRead);
                    pResponseCharacteristic->notify();
                    delay(5);
                }
            }
            file.close();
            applog("Finished sending file %s.\n", filename.c_str());

            delay(10);
            applog("Sending [EOM] marker...\n");
            String eom = "[EOM]";
            pResponseCharacteristic->setValue((uint8_t*)eom.c_str(), eom.length());
            pResponseCharacteristic->notify();
            applog("[EOM] marker sent.\n");
        } else {
            response = "ERROR: Could not open file[EOM]";
            applog("ERROR: Could not open file %s\n", filename.c_str());
        }
      } else {
        response = "ERROR: File not found[EOM]";
        applog("ERROR: File not found: %s\n", filename.c_str());
      }
    } else if (command.startsWith("CMD:format_fs")) {
      if(LittleFS.format()){
        response = "LittleFS formatted successfully.[EOM]";
        shouldRestart = true;
      } else {
        response = "ERROR: Failed to format LittleFS.[EOM]";
      }
    } else {
      response = "Unknown command[EOM]";
    }

    if (response.length() > 0) {
        pResponseCharacteristic->setValue((uint8_t*)response.c_str(), response.length());
        pResponseCharacteristic->notify();
    }

    if (shouldRestart) {
      delay(1000);
      ESP.restart();
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
      log_i("Client Connected\n");
    };

    void onDisconnect(BLEServer* pServer) {
      log_i("Client Disconnected - Restarting Advertising\n");
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
  pCommandCharacteristic->setCallbacks(new CommandCharacteristicCallbacks());

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

void loadSettingsFromLittleFS() {
  log_i("Loading settings from /setting.ini...\n");

  // Initialize global WiFi AP arrays
  for (int i = 0; i < WIFI_MAX_APS; ++i) {
    g_wifi_ssids[i][0] = '\0';
    g_wifi_passwords[i][0] = '\0';
  }
  g_num_wifi_aps = 0; // Reset count before loading

  if (!LittleFS.begin()) {
    log_i("LittleFS Mount Failed. Using default settings.\n");
    return;
  }

  File configFile = LittleFS.open("/setting.ini", "r");
  if (!configFile) {
    log_i("Failed to open /setting.ini. Using default settings.\n");
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
      log_i("Invalid line in setting.ini: %s\r\n", lineBuffer);
      continue;
    }

    *separator = '\0'; // Null-terminate the key part
    char* key = lineBuffer;
    char* value = separator + 1;

    trim_whitespace(key);
    trim_whitespace(value);

    if (strcmp(key, "DEEP_SLEEP_DELAY_MS") == 0) {
      DEEP_SLEEP_DELAY_MS = atol(value);
      log_i("Setting DEEP_SLEEP_DELAY_MS to %lu\r\n", DEEP_SLEEP_DELAY_MS);
    } else if (strcmp(key, "BAT_VOL_MIN") == 0) {
      BAT_VOL_MIN = atof(value);
      log_i("Setting BAT_VOL_MIN to %f\r\n", BAT_VOL_MIN);
    } else if (strcmp(key, "BAT_VOL_MULT") == 0) {
      BAT_VOL_MULT = atof(value);
      log_i("Setting BAT_VOL_MULT to %f\r\n", BAT_VOL_MULT);
    } else if (strcmp(key, "I2S_SAMPLE_RATE") == 0) {
      I2S_SAMPLE_RATE = atoi(value);
      log_i("Setting I2S_SAMPLE_RATE to %d\r\n", I2S_SAMPLE_RATE);
    } else if (strcmp(key, "REC_MAX_S") == 0) {
      REC_MAX_S = atoi(value);
      log_i("Setting REC_MAX_S to %d\r\n", REC_MAX_S);
      MAX_REC_DURATION_MS = REC_MAX_S * 1000; // Recalculate MAX_RECORDING_DURATION_MS
      log_i("Recalculated MAX_REC_DURATION_MS to %lu\n", MAX_REC_DURATION_MS);
    } else if (strcmp(key, "REC_MIN_S") == 0) {
      REC_MIN_S = atoi(value);
      log_i("Setting REC_MIN_S to %d\r\n", REC_MIN_S);
      updateMinAudioFileSize(); // Recalculate MIN_AUDIO_FILE_SIZE_BYTES
    } else if (strcmp(key, "AUDIO_GAIN") == 0) {
      AUDIO_GAIN = atof(value);
      log_i("Setting AUDIO_GAIN to %f\r\n", AUDIO_GAIN);
    } else if (strcmp(key, "VIBRA_STARTUP_MS") == 0) {
      VIBRA_STARTUP_MS = atol(value);
      log_i("Setting VIBRA_STARTUP_MS to %lu\r\n", VIBRA_STARTUP_MS);
    } else if (strcmp(key, "VIBRA_REC_START_MS") == 0) {
      VIBRA_REC_START_MS = atol(value);
      log_i("Setting VIBRA_REC_START_MS to %lu\r\n", VIBRA_REC_START_MS);
    } else if (strcmp(key, "VIBRA_REC_STOP_MS") == 0) {
      VIBRA_REC_STOP_MS = atol(value);
      log_i("Setting VIBRA_REC_STOP_MS to %lu\r\n", VIBRA_REC_STOP_MS);
    } else if (strcmp(key, "HS_HOST") == 0) {
      HS_HOST = strdup(value);
      log_i("Setting HS_HOST to %s\r\n", HS_HOST);
    } else if (strcmp(key, "HS_PORT") == 0) {
      HS_PORT = atoi(value);
      log_i("Setting HS_PORT to %d\r\n", HS_PORT);
    } else if (strcmp(key, "HS_PATH") == 0) {
      HS_PATH = strdup(value);
      log_i("Setting HS_PATH to %s\r\n", HS_PATH);
    } else if (strcmp(key, "HS_USER") == 0) {
      HS_USER = strdup(value);
      log_i("Setting HS_USER to %s\r\n", HS_USER);
    } else if (strcmp(key, "HS_PASS") == 0) {
      HS_PASS = strdup(value);
      log_i("Setting HS_PASS to %s\r\n", HS_PASS);
    } else if (strncmp(key, "W_SSID_", strlen("W_SSID_")) == 0) {
      int apIndex = atoi(key + strlen("W_SSID_"));
      if (apIndex >= 0 && apIndex < WIFI_MAX_APS) {
        strncpy(g_wifi_ssids[apIndex], value, sizeof(g_wifi_ssids[apIndex]) - 1);
        g_wifi_ssids[apIndex][sizeof(g_wifi_ssids[apIndex]) - 1] = '\0'; // Ensure null termination
        log_i("Setting W_SSID_%d to %s\r\n", apIndex, g_wifi_ssids[apIndex]);
      }
    } else if (strncmp(key, "W_PASS_", strlen("W_PASS_")) == 0) {
      int apIndex = atoi(key + strlen("W_PASS_"));
      if (apIndex >= 0 && apIndex < WIFI_MAX_APS) {
        strncpy(g_wifi_passwords[apIndex], value, sizeof(g_wifi_passwords[apIndex]) - 1);
        g_wifi_passwords[apIndex][sizeof(g_wifi_passwords[apIndex]) - 1] = '\0'; // Ensure null termination
        log_i("Setting W_PASS_%d to %s\r\n", apIndex, g_wifi_passwords[apIndex]);
      }
    } else {
      log_i("Unknown setting in setting.ini: %s\r\n", key);
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
  log_i("Configured %d WiFi APs.\r\n", g_num_wifi_aps);

  configFile.close();
  log_i("Settings loaded.\n");
}

