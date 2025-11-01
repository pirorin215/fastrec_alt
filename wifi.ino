#include "fastrec_alt.h"

#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include "esp_wifi.h"

// 詳細な接続情報を表示
void printWiFiDiagnostics() {
  if (!isWiFiConnected()) {
    Serial.println("WiFi not connected.");
    return;
  }
  Serial.println("\n=== WiFi Diagnostics ===");
  Serial.printf("SSID: %s\r\n", WiFi.SSID().c_str());
  Serial.printf("RSSI: %d dBm\r\n", WiFi.RSSI());
  Serial.printf("Channel: %d\r\n", WiFi.channel());
  Serial.printf("IP: %s\r\n", WiFi.localIP().toString().c_str());
  Serial.printf("Gateway: %s\r\n", WiFi.gatewayIP().toString().c_str());
  Serial.printf("MAC: %s\r\n", WiFi.macAddress().c_str());
}

bool isWiFiConnected() {
  return WiFi.status() == WL_CONNECTED;
}

void wifiSetSleep(bool flag) {
  WiFi.setSleep(flag);
}

// Function to synchronize time from NTP or internal RTC
void synchronizeTime(bool waitForNTP) {
  struct tm timeinfo;

  if (waitForNTP) {
    Serial.println("Setting up time synchronization (NTP)...");
    configTime(9 * 60 * 60, 0, "ntp.jst.mfeed.ad.jp", "ntp.nict.jp", "time.google.com");

    const long NTP_TIMEOUT_MS = 10000;  // 10 seconds timeout for NTP sync
    unsigned long startTryTime = millis();
    unsigned long lastCheckTime = millis();

    bool ntp_sync_successful = false;  // New local variable to track NTP success
    // Wait for time to be set, with a timeout
    while (!ntp_sync_successful && (millis() - startTryTime < NTP_TIMEOUT_MS)) {
      // Check for button presses to cancel NTP synchronization
      if (g_startButtonPressedISR) {
        Serial.println("Start Button pressed during NTP sync. Aborting.");
        return; // Abort synchronization try
      }

      if (getLocalTime(&timeinfo)) {
        if (timeinfo.tm_year > (2000 - 1900)) {  // Check if year is after 2000 (epoch is 1970)
          ntp_sync_successful = true;
        }
      }
      if (!ntp_sync_successful) {
        if (millis() - lastCheckTime >= 500) {
          Serial.print(".");
          lastCheckTime = millis();
        }
      }
    }
    Serial.println();  // Newline after dots

    if (ntp_sync_successful) {
      Serial.println(&timeinfo, "Current time from NTP: %A, %B %d %Y %H:%M:%S");
      g_hasTimeBeenSynchronized = true; // Set flag on successful NTP sync
    } else {
      Serial.println("Failed to obtain time from NTP within timeout. Time might be incorrect.");
    }
  } else {  // Not waiting for NTP, so it's not web-synchronized.
    if (getValidRtcTime(&timeinfo)) {
      Serial.println("RTC has a valid time, but no web synchronization was try.");
    }
    else {
      Serial.println("RTC time is not set or invalid, and no web synchronization was try.");
    }
  }
}

void wifiReset() {
  WiFi.disconnect(true, true); // Disconnect and clear credentials
  esp_wifi_stop();
  esp_wifi_start();
}

void initWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.setTxPower(WIFI_POWER_19_5dBm); // 最大出力
  WiFi.setAutoReconnect(true); // 自動再接続を有効化
  WiFi.persistent(true); // 永続的な接続を試みる
}

// Function to connect to WiFi
bool connectToWiFi() {
  Serial.println("Waiting for WiFi connection...");
  updateDisplay("");

  Serial.println("Available WiFi APs:");
  for (int i = 0; i < g_num_wifi_aps; i++) {
    Serial.printf("- %s\r\n", g_wifi_ssids[i]);
  }
  
  unsigned long startWiFiWaitTime = millis();
  
  int initialApIndex = -1; // Will store the AP index to try first
  if (0 <= g_lastConnectedSSIDIndexRTC && g_lastConnectedSSIDIndexRTC < g_num_wifi_aps) {
    initialApIndex = g_lastConnectedSSIDIndexRTC;
    Serial.printf("Prioritizing connection to previously connected SSID index: %d (%s)\r\n", initialApIndex, g_wifi_ssids[initialApIndex]);
  } else {
    Serial.println("No previous successful WiFi connection recorded or index is invalid. Starting from first configured AP.");
    initialApIndex = 0; // Start from the first AP if no prior connection or invalid index
  }

  int currentApIndex = initialApIndex; // Start with the prioritized AP or 0

  const long PER_AP_ATTEMPT_TIMEOUT_MS = 5000; // per AP attempt
   
  wifiReset();

  while (!isWiFiConnected() && (millis() - startWiFiWaitTime < WIFI_CONNECT_TIMEOUT_MS)) {
    if (g_startButtonPressedISR) {
      Serial.println("Start Button pressed during WiFi connection. Aborting.");
      return false;
    }
    wifiReset();

    Serial.printf("Attempting to connect to SSID: %s\r\n", g_wifi_ssids[currentApIndex]);
    WiFi.begin(g_wifi_ssids[currentApIndex], g_wifi_passwords[currentApIndex]);

    unsigned long connectionAttemptStartTime = millis();

    while (WiFi.status() != WL_CONNECTED && (millis() - connectionAttemptStartTime < PER_AP_ATTEMPT_TIMEOUT_MS)) {
      if (g_startButtonPressedISR) {
        Serial.println("Start Button pressed during WiFi connection. Aborting.");
        return false;
      }
      yield();
    }
    Serial.print(".");

    if (WiFi.status() == WL_CONNECTED) {
      Serial.println("\nWiFi connected successfully!");
      g_connectedSSIDIndex = currentApIndex; // Use the already known index
      g_lastConnectedSSIDIndexRTC = currentApIndex; // Store the successfully connected index in RTC
      printWiFiDiagnostics(); // 詳細情報を表示
      return true; // Connected to an AP, return true
    } else {
      Serial.printf("\nFailed to connect to %s within %ldms. Trying next AP.\r\n", g_wifi_ssids[currentApIndex], PER_AP_ATTEMPT_TIMEOUT_MS);
      currentApIndex = (currentApIndex + 1) % g_num_wifi_aps;
    }
  }

  Serial.println("\nFailed to connect to any configured WiFi AP within timeout.");
  return false; // Failed to connect to any AP
}

bool checkAuthentication(const char* host, int port, const char* path, const char* user, const char* password) {
  Serial.printf("Checking authentication to https://%s:%d%s\r\n", host, port, path);

  WiFiClientSecure client;
  client.setInsecure(); // Allow self-signed certificates

  if (!client.connect(host, port)) {
    Serial.println("Authentication check: Connection failed!");
    return false;
  }

  char authBuffer[64];
  snprintf(authBuffer, sizeof(authBuffer), "%s:%s", user, password);

  // Send GET request
  client.print("GET ");
  client.print(path);
  client.println(" HTTP/1.1");
  client.print("Host: ");
  client.println(host);
  client.print("Authorization: Basic ");
  client.println(base64::encode(String(authBuffer))); // ここのString型使用は仕方ない
  client.println("Connection: close"); // Close connection after response
  client.println(); // End of headers

  // Read server response
  unsigned long timeout = millis();
  char responseLineBuffer[256];
  while (client.connected() && (millis() - timeout < 10000)) { // 10 second timeout
    if (client.available()) {
      int bytesRead = client.readBytesUntil('\r', responseLineBuffer, sizeof(responseLineBuffer) - 1);
      if (bytesRead > 0) {
        responseLineBuffer[bytesRead] = '\0'; // Null-terminate the received line
        Serial.println(responseLineBuffer);

        if (strstr(responseLineBuffer, "HTTP/1.1 200 OK") != NULL) {
          Serial.println("Authentication check: Success (200 OK).");
          client.stop();
          return true;
        } else if (strstr(responseLineBuffer, "HTTP/1.1 401 Unauthorized") != NULL) {
          Serial.println("Authentication check: Failed (401 Unauthorized).");
          client.stop();
          return false;
        }
        // If other response codes are received, continue reading or handle as failure
      }
    }
  }

  Serial.println("Authentication check: Failed or timed out!");
  client.stop();
  return false;
}

// Function to upload audio data via HTTP POST with multipart/form-data
bool uploadAudioFileViaHTTP(const char* filename, const char* host, int port, const char* path, const char* user, const char* password) {
  Serial.printf("Uploading file %s to https://%s:%d%s\r\n", filename, host, port, path);

  WiFiClientSecure client;
  client.setInsecure();

  File audioFile = LittleFS.open(filename, FILE_READ);
  if (!client.connect(host, port)) {
    Serial.println("Connection failed!");
    client.stop();
    audioFile.close();
    return false;
  }

  char boundaryBuffer[70];
  snprintf(boundaryBuffer, sizeof(boundaryBuffer), "--------------------------%lu", micros());

  char contentTypeBuffer[100];
  snprintf(contentTypeBuffer, sizeof(contentTypeBuffer), "multipart/form-data; boundary=%s", boundaryBuffer);

  char authBuffer[64];
  snprintf(authBuffer, sizeof(authBuffer), "%s:%s", user, password);

  char footerPartBuffer[100];
  snprintf(footerPartBuffer, sizeof(footerPartBuffer), "\r\n--%s--\r\n", boundaryBuffer);

  size_t audioFileSize = audioFile.size(); // Get audio file size before passing to function

  // Construct the HTTP POST request headers
  client.print("POST ");
  client.print(path);
  client.println(" HTTP/1.1");
  client.print("Host: ");
  client.println(host);
  client.print("Authorization: Basic ");
  client.println(base64::encode(String(authBuffer))); // ここのString型使用は仕方ない
  client.print("Content-Type: ");
  client.println(contentTypeBuffer);

  // Construct the multipart body parts
  char headerPartBuffer[256];
  snprintf(headerPartBuffer, sizeof(headerPartBuffer),
           "--%s\r\nContent-Disposition: form-data; name=\"audio_data\"; filename=\"%s\"\r\nContent-Type: audio/wav\r\n\r\n",
           boundaryBuffer, filename);

  // Calculate content length, including the footer that will be sent later
  char tempFooterPartBuffer[100]; // Temporary buffer to calculate footer size
  snprintf(tempFooterPartBuffer, sizeof(tempFooterPartBuffer), "\r\n--%s--\r\n", boundaryBuffer);
  size_t contentLength = strlen(headerPartBuffer) + audioFileSize + strlen(tempFooterPartBuffer);

  client.print("Content-Length: ");
  client.println(contentLength);
  client.println();  // End of headers

  // Send the multipart header part
  client.print(headerPartBuffer);

  // Send the file content in chunks
  uint8_t buffer[1024];
  size_t totalBytesSent = 0;
  // size_t audioFileSize = audioFile.size(); // This line was removed to fix redeclaration
  int lastReportedProgress = 0;

  while (audioFile.available()) {
    size_t bytesRead = audioFile.read(buffer, sizeof(buffer));
    client.write(buffer, bytesRead);
    totalBytesSent += bytesRead;

    char cTmp[4];

    int uploadProgress = (totalBytesSent * 100) / audioFileSize;
    if (uploadProgress >= lastReportedProgress + 10) {
      Serial.printf("Upload progress: %d%%\r\n", uploadProgress);
      snprintf(cTmp, sizeof(cTmp), "%3d", uploadProgress);
      updateDisplay(cTmp);
      lastReportedProgress = uploadProgress - (uploadProgress % 10); // Round down to nearest 10
    }
  }
  Serial.println("Upload progress: 100%"); // Ensure 100% is always printed at the end

  client.print(footerPartBuffer); // Send the multipart footer part

  // Read server response
  unsigned long timeout = millis();
  char responseLineBuffer[256];
  while (client.connected() && (millis() - timeout < 10000)) {  // 10 second timeout
    if (client.available()) {
      int bytesRead = client.readBytesUntil('\r', responseLineBuffer, sizeof(responseLineBuffer) - 1);
      if (bytesRead > 0) {
        responseLineBuffer[bytesRead] = '\0';  // Null-terminate the received line
        Serial.println(responseLineBuffer);
        if (strstr(responseLineBuffer, "HTTP/1.1 200 OK") != NULL) {  // Check for successful HTTP response
          Serial.println("File uploaded successfully!");
          client.stop();
          audioFile.close();
          return true;
        } else if (strstr(responseLineBuffer, "HTTP/1.1 401 Unauthorized") != NULL) {
          Serial.println("Upload failed: 401 Unauthorized. Check HS_USER and HS_PASS.");
          audioFile.close();
          client.stop();
          return false; // Indicate failure
        }
      }
    }
  }

  Serial.println("Upload failed or timed out!");
  audioFile.close(); // Ensure file is closed on timeout/failure
  client.stop();
  return false;
}

