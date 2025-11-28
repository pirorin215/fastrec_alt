#include "fastrec_alt.h"
#include <Base64.h>

#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include "esp_wifi.h"
#include <esp_sntp.h>

// 詳細な接続情報を表示
void printWiFiDiagnostics() {
  if (!isWiFiConnected()) {
    applog("WiFi not connected.");
    return;
  }
  applog("=== WiFi Diagnostics ===");
  applog("SSID: %s", WiFi.SSID().c_str());
  applog("RSSI: %d dBm", WiFi.RSSI());
  applog("Channel: %d", WiFi.channel());
  applog("IP: %s", WiFi.localIP().toString().c_str());
  applog("Gateway: %s", WiFi.gatewayIP().toString().c_str());
  applog("MAC: %s", WiFi.macAddress().c_str());
}

bool isWiFiConnected() {
  return WiFi.status() == WL_CONNECTED;
}

void wifiSetSleep(bool flag) {
  WiFi.setSleep(flag);
}

// SNTP同期が完了したときに呼ばれるコールバック関数
void timeSyncNotificationCallback(struct timeval *tv) {
    g_ntpSyncEnd = true;
}

// Function to time drift analysis results
void timeDriftAnalysis(time_t mcu_epoch_before_sync_start, unsigned long millis_at_sync_start) {
  struct tm timeinfo;
  getLocalTime(&timeinfo); // Get the current time from NTP (now calibrated)
  time_t current_ntp_epoch_s = mktime(&timeinfo); // Calculate current NTP epoch regardless of drift calculation readiness

  // Check against global g_last_ntp_epoch_s
  if (g_last_ntp_epoch_s != 0 && mcu_epoch_before_sync_start != 0) {
    unsigned long millis_at_sync_end = millis();
    double sync_active_duration_s = (double)(millis_at_sync_end - millis_at_sync_start) / 1000.0;

    long ntp_elapsed_s = current_ntp_epoch_s - g_last_ntp_epoch_s;
    
    // Calculate MCU elapsed time from g_last_ntp_epoch_s until current_ntp_epoch_s
    double mcu_elapsed_s = (double)(mcu_epoch_before_sync_start - g_last_ntp_epoch_s) + sync_active_duration_s;
    double drift_s = mcu_elapsed_s - ntp_elapsed_s;

    applog("Time drift analysis:");
    applog("  Previous NTP epoch: %ld s", g_last_ntp_epoch_s);
    applog("  Current NTP epoch:  %ld s", current_ntp_epoch_s);
    applog("  NTP elapsed:        %ld s", ntp_elapsed_s);
    applog("  MCU elapsed:        %.3f s", mcu_elapsed_s);
    applog("  Drift:              %.3f s", drift_s);
    if (ntp_elapsed_s > 0) {
      double logged_drift_rate_ratio = mcu_elapsed_s / (double)ntp_elapsed_s;
      applog("  Drift Rate (ratio): %.5f", logged_drift_rate_ratio);
    } else {
      applog("  Drift Rate:         N/A (NTP elapsed is zero)");
    }
  } else {
    applog("Time drift analysis: Skipping, not enough historical data for full drift calculation.");
  }
  // Update RTC variables for the next cycle, regardless of whether drift was calculated or skipped.
  g_last_ntp_epoch_s = current_ntp_epoch_s;
}
// Function to synchronize time from NTP or internal RTC
void synchronizeTime() {
  struct tm timeinfo;
  const long NTP_TIMEOUT_MS = 10000;  // 10 seconds timeout for NTP sync
  
  applog("Setting up time synchronization (NTP)...");

  // Capture MCU's current RTC time and millis() before starting NTP synchronization
  time_t mcu_epoch_before_sync_start = 0;
  struct tm timeinfo_before_sync;
  if(getLocalTime(&timeinfo_before_sync)) {
    mcu_epoch_before_sync_start = mktime(&timeinfo_before_sync);
  }

  unsigned long millis_at_sync_start = millis(); // Millis() at the very beginning of the sync attempt

  sntp_set_time_sync_notification_cb(timeSyncNotificationCallback); // SNTPコールバック設定

  configTime(9 * 60 * 60, 0, "ntp.jst.mfeed.ad.jp", "ntp.nict.jp", "time.google.com"); // 時刻同期開始（すぐには同期されない）

  unsigned long startTryTime = millis();
  while (true) {
    if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
      applog("Start Button pressed during NTP sync. Aborting.");
      break;
    }
    if(millis() - startTryTime > NTP_TIMEOUT_MS) {
      applog("time out NTP sync. Aborting.");
      break;
    }
    if(g_ntpSyncEnd) {
      applog("NTP sync callback.");
      timeDriftAnalysis(mcu_epoch_before_sync_start, millis_at_sync_start);
      break;
    }
  }
  sntp_set_time_sync_notification_cb(NULL); // SNTPコールバックを解除
  g_ntpSyncEnd = false;
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
  applog("Waiting for WiFi connection...");
  updateDisplay("");

  applog("Available WiFi APs:");
  for (int i = 0; i < g_num_wifi_aps; i++) {
    applog("- %s", g_wifi_ssids[i]);
  }
  
  unsigned long startWiFiWaitTime = millis();
  
  int initialApIndex = -1; // Will store the AP index to try first
  if (0 <= g_lastConnectedSSIDIndexRTC && g_lastConnectedSSIDIndexRTC < g_num_wifi_aps) {
    initialApIndex = g_lastConnectedSSIDIndexRTC;
    applog("Prioritizing connection to previously connected SSID index: %d (%s)", initialApIndex, g_wifi_ssids[initialApIndex]);
  } else {
    applog("No previous successful WiFi connection recorded or index is invalid. Starting from first configured AP.");
    initialApIndex = 0; // Start from the first AP if no prior connection or invalid index
  }

  int currentApIndex = initialApIndex; // Start with the prioritized AP or 0

  const long PER_AP_ATTEMPT_TIMEOUT_MS = 5000; // per AP attempt
   
  wifiReset();

  while (!isWiFiConnected() && (millis() - startWiFiWaitTime < WIFI_CONNECT_TIMEOUT_MS)) {
    if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
      applog("Start Button pressed during WiFi connection. Aborting.");
      return false;
    }

    wifiReset();

    applog("Attempting to connect to SSID: %s", g_wifi_ssids[currentApIndex]);
    WiFi.begin(g_wifi_ssids[currentApIndex], g_wifi_passwords[currentApIndex]);

    unsigned long connectionAttemptStartTime = millis();

    while (WiFi.status() != WL_CONNECTED && (millis() - connectionAttemptStartTime < PER_AP_ATTEMPT_TIMEOUT_MS)) {
      if (digitalRead(REC_BUTTON_GPIO) == HIGH) {
        applog("Start Button pressed during WiFi connection. Aborting.");
        return false;
      }
      yield();
    }

    if (WiFi.status() == WL_CONNECTED) {
      applog("\nWiFi connected successfully!");
      g_connectedSSIDIndex = currentApIndex; // Use the already known index
      g_lastConnectedSSIDIndexRTC = currentApIndex; // Store the successfully connected index in RTC
      printWiFiDiagnostics(); // 詳細情報を表示
      return true; // Connected to an AP, return true
    } else {
      applog("\nFailed to connect to %s within %ldms. Trying next AP.", g_wifi_ssids[currentApIndex], PER_AP_ATTEMPT_TIMEOUT_MS);
      currentApIndex = (currentApIndex + 1) % g_num_wifi_aps;
    }
  }

  applog("\r\nFailed to connect to any configured WiFi AP within timeout.");
  return false; // Failed to connect to any AP
}

bool checkAuthentication(const char* host, int port, const char* path, const char* user, const char* password) {
  applog("Checking authentication to https://%s:%d%s", host, port, path);

  WiFiClientSecure client;
  client.setInsecure(); // Allow self-signed certificates

  if (!client.connect(host, port)) {
    applog("Authentication check: Connection failed!");
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
  char encodedAuthBuffer[128];
  base64_encode(encodedAuthBuffer, authBuffer, strlen(authBuffer));
  client.println(encodedAuthBuffer);
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
        applog("%s", responseLineBuffer);

        if (strstr(responseLineBuffer, "HTTP/1.1 200 OK") != NULL) {
          applog("Authentication check: Success (200 OK).");
          client.stop();
          return true;
        } else if (strstr(responseLineBuffer, "HTTP/1.1 401 Unauthorized") != NULL) {
          applog("Authentication check: Failed (401 Unauthorized).");
          client.stop();
          return false;
        }
        // If other response codes are received, continue reading or handle as failure
      }
    }
    // Add a small delay to prevent busy-waiting
    delay(10);
  }

  applog("Authentication check: Failed or timed out!");
  client.stop();
  return false;
}

// Function to upload audio data via HTTP POST with multipart/form-data
bool uploadAudioFileViaHTTP(const char* filename, const char* host, int port, const char* path, const char* user, const char* password) {
  applog("Uploading file %s to https://%s:%d%s", filename, host, port, path);

  WiFiClientSecure client;
  client.setInsecure();

  File audioFile = LittleFS.open(filename, FILE_READ);
  if (!client.connect(host, port)) {
    applog("Connection failed!");
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
  char encodedAuthBuffer[128];
  base64_encode(encodedAuthBuffer, authBuffer, strlen(authBuffer));
  client.println(encodedAuthBuffer);
  client.print("Content-Type: ");
  client.println(contentTypeBuffer);

  // Construct the multipart body parts
  char headerPartBuffer[256];
  snprintf(headerPartBuffer, sizeof(headerPartBuffer),
           "--%s\r\nContent-Disposition: form-data; name=\"audio_data\"; filename=\" %s\"\r\nContent-Type: audio/wav\r\n\r\n",
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
      applog("Upload progress: %d%%", uploadProgress);
      snprintf(cTmp, sizeof(cTmp), "%3d", uploadProgress);
      updateDisplay(cTmp);
      lastReportedProgress = uploadProgress - (uploadProgress % 10); // Round down to nearest 10
    }
  }
  applog("Upload progress: 100%%");

  client.print(footerPartBuffer);

  // Read server response
  unsigned long timeout = millis();
  char responseLineBuffer[256];
  while (client.connected() && (millis() - timeout < 10000)) {  // 10 second timeout
    if (client.available()) {
      int bytesRead = client.readBytesUntil('\r', responseLineBuffer, sizeof(responseLineBuffer) - 1);
      if (bytesRead > 0) {
        responseLineBuffer[bytesRead] = '\0';  // Null-terminate the received line
        applog("%s", responseLineBuffer);
        if (strstr(responseLineBuffer, "HTTP/1.1 200 OK") != NULL) {  // Check for successful HTTP response
          applog("File uploaded successfully!");
          client.stop();
          audioFile.close();
          return true;
        } else if (strstr(responseLineBuffer, "HTTP/1.1 401 Unauthorized") != NULL) {
          applog("Upload failed: 401 Unauthorized. Check HS_USER and HS_PASS.");
          audioFile.close();
          client.stop();
          return false; // Indicate failure
        }
      }
    }
    // Add a small delay to prevent busy-waiting
    delay(10);
  }

  applog("Upload failed or timed out!");
  audioFile.close(); // Ensure file is closed on timeout/failure
  client.stop();
  return false;
}
