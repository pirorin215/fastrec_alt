#include "fastrec_alt.h"
#include <SSD1315.h>

#include <cmath>

SSD1315 display;

const int MAX_CHARS_PER_LINE = 15;
const int FONT_SIZE = 5;
const int LINE_HEIGHT = 10;  // Assuming 10 pixels per line for font size 5

void setLcdBrightness(uint8_t brightness) {
  display.setBrightness(brightness);
}

void initSSD() {
  applog("initSSD");

  Wire.begin(LCD_SDA_GPIO, LCD_SCL_GPIO, 100000L);

  display.begin();
  display.setRotation(2);       // rotate 180 degrees
  display.setBrightness(0x00);

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

void displayStatus(const char* msg) {

  char line1[MAX_CHARS_PER_LINE+1];

  if (isBLEConnected()) {
    std::string displayBleCommand = "BLE ";
    if (!g_lastBleCommand.empty()) {
      // Ensure the command fits, leaving space for "BLE "
      int remainingChars = MAX_CHARS_PER_LINE - displayBleCommand.length();
      if (g_lastBleCommand.length() > remainingChars) {
        displayBleCommand += g_lastBleCommand.substr(0, remainingChars);
      } else {
        displayBleCommand += g_lastBleCommand;
      }
    }
    strncpy(line1, displayBleCommand.c_str(), MAX_CHARS_PER_LINE);
    line1[MAX_CHARS_PER_LINE] = '\0';
  } else {
    const char* appStateToDisplay = appStateStrings[g_currentAppState];
    snprintf(line1, sizeof(line1), "% -*s", MAX_CHARS_PER_LINE, appStateToDisplay);
  }
  displayLine(0, line1); 
 
  // 2行目: フラッシュメモリ空き容量と電池残量
  char line2[MAX_CHARS_PER_LINE+1];

  // Calculate Battery Level (BL)
  int batteryLevel = (int)(((g_currentBatteryVoltage - BAT_VOL_MIN) / 1.0) * 100);
  if (batteryLevel < 0) batteryLevel = 0;
  if (batteryLevel > 100) batteryLevel = 100;
 
  // FS display (no decimal)
  int fsUsage = (int)ceil(getLittleFSUsagePercentage()); 

  char fsUsageStr[7];
  if (countAudioFiles() == 0) {
    fsUsageStr[0] = '\0';
  } else {
    snprintf(fsUsageStr, sizeof(fsUsageStr), "FS:%3d", fsUsage);
  }
  snprintf(line2, sizeof(line2), "%6s BL:%3d", fsUsageStr, batteryLevel);
  displayLine(1, line2); 
 
  // 3行目:時刻
  char line3[MAX_CHARS_PER_LINE+1];
  getFormattedRtcTime(line3, sizeof(line3));
  displayLine(2, line3);

  // 4行目
  char line4[MAX_CHARS_PER_LINE+1];
  if(msg[0] == '\0') {
    line4[0] = '\0';
  } else {
    strncpy(line4, msg, MAX_CHARS_PER_LINE);
  }
  line4[MAX_CHARS_PER_LINE] = '\0';
  displayLine(3, line4);
}

void displaySetup() {
  displayLine(0, "setting.ini");
  displayLine(1, "not found.");
  displayLine(2, "Please send");
  displayLine(3, "from BLE tool.");
}

void updateDisplay(const char* msg) {
  display.clear();
  switch (g_currentAppState) {

    case SETUP:
      displaySetup();
      break;
    default:
      displayStatus(msg); // Pass the determined message for line 3
      break;
  }
  display.display();
}
