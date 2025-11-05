#include "fastrec_alt.h"

#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include "esp_wifi.h"

// 詳細な接続情報を表示
void printWiFiDiagnostics() {
  if (!isWiFiConnected()) {
    app_log_i("WiFi not connected.\n");
    return;
  }
  app_log_i("\n=== WiFi Diagnostics ===\n");
  app_log_i("SSID: %s\r\n", WiFi.SSID().c_str());
  app_log_i("RSSI: %d dBm\r\n", WiFi.RSSI());
  app_log_i("Channel: %d\r\n", WiFi.channel());
  app_log_i("IP: %s\r\n", WiFi.localIP().toString().c_str());
  app_log_i("Gateway: %s\r\n", WiFi.gatewayIP().toString().c_str());
  app_log_i("MAC: %s\r\n", WiFi.macAddress().c_str());
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
    app_log_i("Setting up time synchronization (NTP)...\n");
    configTime(9 * 60 * 60, 0, "ntp.jst.mfeed.ad.jp", "ntp.nict.jp", "time.google.com");

    const long NTP_TIMEOUT_MS = 10000;  // 10 seconds timeout for NTP sync
    unsigned long startTryTime = millis();
    unsigned long lastCheckTime = millis();

    bool ntp_sync_successful = false;  // New local variable to track NTP success
    // Wait for time to be set, with a timeout
    while (!ntp_sync_successful && (millis() - startTryTime < NTP_TIMEOUT_MS)) {
      // Check for button presses to cancel NTP synchronization
      if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
        app_log_i("Start Button pressed during NTP sync. Aborting.\n");
        return;
      }
      if (getLocalTime(&timeinfo)) {
        if (timeinfo.tm_year > (2000 - 1900)) {  // Check if year is after 2000 (epoch is 1970)
          ntp_sync_successful = true;
        }
      }
      if (!ntp_sync_successful) {
        if (millis() - lastCheckTime >= 500) {
          lastCheckTime = millis();
        }
      }
    }

    if (ntp_sync_successful) {
      char time_buf[64];
      strftime(time_buf, sizeof(time_buf), "%A, %B %d %Y %H:%M:%S", &timeinfo);
      app_log_i("Current time from NTP: %s\n", time_buf);
      g_hasTimeBeenSynchronized = true; // Set flag on successful NTP sync
    } else {
      app_log_i("Failed to obtain time from NTP within timeout. Time might be incorrect.\n");
    }
  } else {  // Not waiting for NTP, so it's not web-synchronized.
    if (getValidRtcTime(&timeinfo)) {
      app_log_i("RTC has a valid time, but no web synchronization was try.\n");
    }
    else {
      app_log_i("RTC time is not set or invalid, and no web synchronization was try.\n");
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
  app_log_i("Waiting for WiFi connection...\n");
  updateDisplay("");

  app_log_i("Available WiFi APs:\n");
  for (int i = 0; i < g_num_wifi_aps; i++) {
    app_log_i("- %s\r\n", g_wifi_ssids[i]);
  }
  
  unsigned long startWiFiWaitTime = millis();
  
  int initialApIndex = -1; // Will store the AP index to try first
  if (0 <= g_lastConnectedSSIDIndexRTC && g_lastConnectedSSIDIndexRTC < g_num_wifi_aps) {
    initialApIndex = g_lastConnectedSSIDIndexRTC;
    app_log_i("Prioritizing connection to previously connected SSID index: %d (%s)\r\n", initialApIndex, g_wifi_ssids[initialApIndex]);
  } else {
    app_log_i("No previous successful WiFi connection recorded or index is invalid. Starting from first configured AP.\n");
    initialApIndex = 0; // Start from the first AP if no prior connection or invalid index
  }

  int currentApIndex = initialApIndex; // Start with the prioritized AP or 0

  const long PER_AP_ATTEMPT_TIMEOUT_MS = 5000; // per AP attempt
   
  wifiReset();

  while (!isWiFiConnected() && (millis() - startWiFiWaitTime < WIFI_CONNECT_TIMEOUT_MS)) {
    if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
      app_log_i("Start Button pressed during WiFi connection. Aborting.\n");
      return false;
    }

    wifiReset();

    app_log_i("Attempting to connect to SSID: %s\r\n", g_wifi_ssids[currentApIndex]);
    WiFi.begin(g_wifi_ssids[currentApIndex], g_wifi_passwords[currentApIndex]);

    unsigned long connectionAttemptStartTime = millis();

    while (WiFi.status() != WL_CONNECTED && (millis() - connectionAttemptStartTime < PER_AP_ATTEMPT_TIMEOUT_MS)) {
      if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
        app_log_i("Start Button pressed during WiFi connection. Aborting.\n");
        return false;
      }
      yield();
    }

    if (WiFi.status() == WL_CONNECTED) {
      app_log_i("\nWiFi connected successfully!\n");
      g_connectedSSIDIndex = currentApIndex; // Use the already known index
      g_lastConnectedSSIDIndexRTC = currentApIndex; // Store the successfully connected index in RTC
      printWiFiDiagnostics(); // 詳細情報を表示
      return true; // Connected to an AP, return true
    } else {
      app_log_i("\nFailed to connect to %s within %ldms. Trying next AP.\r\n", g_wifi_ssids[currentApIndex], PER_AP_ATTEMPT_TIMEOUT_MS);
      currentApIndex = (currentApIndex + 1) % g_num_wifi_aps;
    }
  }

  app_log_i("\nFailed to connect to any configured WiFi AP within timeout.\n");
  return false; // Failed to connect to any AP
}

bool checkAuthentication(const char* host, int port, const char* path, const char* user, const char* password) {
  app_log_i("Checking authentication to https://%s:%d%s\r\n", host, port, path);

  WiFiClientSecure client;
  client.setInsecure(); // Allow self-signed certificates

  if (!client.connect(host, port)) {
    app_log_i("Authentication check: Connection failed!\n");
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
        app_log_i("%s\n", responseLineBuffer);

        if (strstr(responseLineBuffer, "HTTP/1.1 200 OK") != NULL) {
          app_log_i("Authentication check: Success (200 OK).\n");
          client.stop();
          return true;
        } else if (strstr(responseLineBuffer, "HTTP/1.1 401 Unauthorized") != NULL) {
          app_log_i("Authentication check: Failed (401 Unauthorized).\n");
          client.stop();
          return false;
        }
        // If other response codes are received, continue reading or handle as failure
      }
    }
  }

  app_log_i("Authentication check: Failed or timed out!\n");
  client.stop();
  return false;
}

// Function to upload audio data via HTTP POST with multipart/form-data
bool uploadAudioFileViaHTTP(const char* filename, const char* host, int port, const char* path, const char* user, const char* password) {
  log_i("Uploading file %s to https://%s:%d%s\r\n", filename, host, port, path);

  WiFiClientSecure client;
  client.setInsecure();

  File audioFile = LittleFS.open(filename, FILE_READ);
  if (!client.connect(host, port)) {
    log_i("Connection failed!\n");
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
      log_i("Upload progress: %d%%\r\n", uploadProgress);
      snprintf(cTmp, sizeof(cTmp), "%3d", uploadProgress);
      updateDisplay(cTmp);
      lastReportedProgress = uploadProgress - (uploadProgress % 10); // Round down to nearest 10
    }
  }
  log_i("Upload progress: 100%%\n");

  client.print(footerPartBuffer);

  // Read server response
  unsigned long timeout = millis();
  char responseLineBuffer[256];
  while (client.connected() && (millis() - timeout < 10000)) {  // 10 second timeout
    if (client.available()) {
      int bytesRead = client.readBytesUntil('\r', responseLineBuffer, sizeof(responseLineBuffer) - 1);
      if (bytesRead > 0) {
        responseLineBuffer[bytesRead] = '\0';  // Null-terminate the received line
        log_i("%s\n", responseLineBuffer);
        if (strstr(responseLineBuffer, "HTTP/1.1 200 OK") != NULL) {  // Check for successful HTTP response
          log_i("File uploaded successfully!\n");
          client.stop();
          audioFile.close();
          return true;
        } else if (strstr(responseLineBuffer, "HTTP/1.1 401 Unauthorized") != NULL) {
          log_i("Upload failed: 401 Unauthorized. Check HS_USER and HS_PASS.\n");
          audioFile.close();
          client.stop();
          return false; // Indicate failure
        }
      }
    }
  }

  log_i("Upload failed or timed out!\n");
  audioFile.close(); // Ensure file is closed on timeout/failure
  client.stop();
  return false;
}

