#include "fastrec_alt.h"
#include <SSD1315.h>
#include <WiFi.h>

SSD1315 display;

const int MAX_CHARS_PER_LINE = 15;
const int FONT_SIZE = 5;
const int LINE_HEIGHT = 10;  // Assuming 10 pixels per line for font size 5

void initSSD() {
  Wire.begin(LCD_SDA_GPIO, LCD_SCL_GPIO);

  display.begin();
  display.setRotation(2);       // rotate 180 degrees
  //display.setBrightness(0x08);
  display.setBrightness(0xFF);

  // Update display with initial status
  float usagePercentage = getLittleFSUsagePercentage();
  updateDisplay("");
}

void displaySleep(bool flag) {
  display.clear();
  display.sleep(flag);
}

void displayLine(uint8_t lineNumber, const char* text) {
  if(lineNumber > 3) {
    return;
  }
  char display_buffer[MAX_CHARS_PER_LINE + 1];  // +1 for null terminator
  strncpy(display_buffer, text, MAX_CHARS_PER_LINE);
  display_buffer[MAX_CHARS_PER_LINE] = '\0';  // Ensure null-termination

  display.drawString(0, lineNumber * LINE_HEIGHT, display_buffer, FONT_SIZE);
}

void drawWifiSignal() {
  int rssi = WiFi.RSSI();

  if (rssi == 0) { // If not connected, don't draw anything
    //Serial.println("WiFi not connected (RSSI is 0), not drawing pictogram.");
    return;
  }

  int level = 0;
  if (rssi > RSSI_LEVEL_4_THRESHOLD) {
    level = 4;
  } else if (rssi > RSSI_LEVEL_3_THRESHOLD) {
    level = 3;
  } else if (rssi > RSSI_LEVEL_2_THRESHOLD) {
    level = 2;
  } else {
    level = 1;
  }

  //Serial.printf("RSSI: %2d, Level: %d\r\n", rssi, level);

  // Bar 1
  if (level >= 1) display.drawRect(61, 6, 62, 8, true);
  // Bar 2
  if (level >= 2) display.drawRect(64, 4, 65, 8, true);
  // Bar 3
  if (level >= 3) display.drawRect(67, 2, 68, 8, true);
  // Bar 4
  if (level >= 4) display.drawRect(70, 0, 71, 8, true);
}

void displayUpload(const char* progress) {
  char files[4][MAX_FILENAME_LENGTH]; // Declare a fixed-size array of filenames
  int numFiles = getLatestAudioFilenames(files, 4, false); // Call with array and size, newest first
  char statusStrBuffer[MAX_CHARS_PER_LINE+1];
  snprintf(statusStrBuffer, sizeof(statusStrBuffer), "%3s %-6s %2d",progress, appStateStrings[g_currentAppState], abs(WiFi.RSSI()));
  displayLine(0, statusStrBuffer);

  int progressBarEndX = (int)((atoi(progress) / 100.0) * SSD1315_WIDTH) - 1;
  if (progressBarEndX >= 0) {
    display.drawRect(0, 0, progressBarEndX, 0, true);
  }

  for (int i = 0; i < numFiles; ++i) {
    char timeStr[MAX_CHARS_PER_LINE+1]; // %m/%d %k:%M:%S -> 01/01 00:00:00
    struct tm timeinfo;
    if (parseFilenameToTm(files[i], &timeinfo)) {
      strftime(timeStr, sizeof(timeStr), "%m/%d %k:%M:%S", &timeinfo);
    } else {
      strncpy(timeStr, "Invalid Time", sizeof(timeStr));
      timeStr[sizeof(timeStr) - 1] = '\0';
    }
    displayLine(i+1, timeStr);
  }
  drawWifiSignal();
}

void displayStatus(const char* msg) {

  // 1行目: AppState(6桁) 空白(4桁) WifiのRSSI(2桁) ピクト
  char line1[MAX_CHARS_PER_LINE+1];
  char rssiStr[4];
  if (WiFi.RSSI() == 0) {
    snprintf(rssiStr, sizeof(rssiStr), "  ");
  } else {
    snprintf(rssiStr, sizeof(rssiStr), "%2d", abs(WiFi.RSSI()));
  }
  snprintf(line1, sizeof(line1), "%-6s    %2s", appStateStrings[g_currentAppState], rssiStr);
  displayLine(0, line1);
 
  // 2行目: フラッシュメモリ空き容量と電池残量
  char line2[MAX_CHARS_PER_LINE+1];

  // Calculate Battery Level (BL)
  int batteryLevel = (int)(((g_currentBatteryVoltage - BAT_VOL_MIN) / 1.0) * 100);
  if (batteryLevel < 0) batteryLevel = 0;
  if (batteryLevel > 100) batteryLevel = 100;
 
  // FS display (no decimal)
  int fsUsage = (int)getLittleFSUsagePercentage();

  // If usage is 0, check for WAV files. If present, force usage to 1%.
  if (fsUsage == 0 && countAudioFiles() > 0) {
    fsUsage = 1;
  }

  char fsUsageStr[4]; // 3 chars + null terminator
  if (fsUsage == 0) {
    snprintf(fsUsageStr, sizeof(fsUsageStr), "   "); // 3 spaces
  } else {
    snprintf(fsUsageStr, sizeof(fsUsageStr), "%3d", fsUsage);
  }
  snprintf(line2, sizeof(line2), "FS:%s BL:%3d", fsUsageStr, batteryLevel);
  displayLine(1, line2);
 
  // 3行目:時刻
  char line3[MAX_CHARS_PER_LINE+1];
  getFormattedRtcTime(line3, sizeof(line3));
  displayLine(2, line3);

  // 4行目
  char line4[MAX_CHARS_PER_LINE+1];
  if(msg[0] == '\0') {
    if (g_connectedSSIDIndex != -1 && g_connectedSSIDIndex < g_num_wifi_aps) {
      strncpy(line4, g_wifi_ssids[g_connectedSSIDIndex], MAX_CHARS_PER_LINE);
    } else {
      line4[0] = '\0';
    }
  } else {
    strncpy(line4, msg, MAX_CHARS_PER_LINE);
  }
  line4[MAX_CHARS_PER_LINE] = '\0';
  displayLine(3, line4);

  // wifi強度ピクト　
  drawWifiSignal();
}

void updateDisplay(const char* msg) {
  display.clear();
  switch (g_currentAppState) {
    case UPLOAD:
      displayUpload(msg);
      break;
    default:
      displayStatus(msg); // Pass the determined message for line 3
      break;
  }
  display.display();
}

