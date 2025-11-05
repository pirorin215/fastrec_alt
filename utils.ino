#include "fastrec_alt.h"
#include <stdarg.h>

void applog(const char *format, ...) {
    char loc_buf[64];
    char * temp = loc_buf;
    struct tm timeinfo;
    if (getValidRtcTime(&timeinfo)) { // Use getValidRtcTime to ensure time is valid
        strftime(loc_buf, sizeof(loc_buf), "[%Y-%m-%d %H:%M:%S] ", &timeinfo);
    } else {
        strcpy(loc_buf, "[<no time>] ");
    }

    va_list arg;
    va_start(arg, format);
    int len = vsnprintf(NULL, 0, format, arg);
    va_end(arg);
    
    temp = (char*)malloc(strlen(loc_buf) + len + 1);
    if (temp != NULL) {
        strcpy(temp, loc_buf);
        va_start(arg, format);
        vsnprintf(temp + strlen(loc_buf), len + 1, format, arg);
        va_end(arg);
        Serial.print(temp);
        free(temp);
    }
}


void onboard_led(bool bOn) {
  // LOWで点灯、HIGHで消灯で紛らわしいので関数化してる
  applog("onboard_led %d\r\n", bOn);
  if (bOn) {
    digitalWrite(LED_BUILTIN, LOW);
  } else {
    digitalWrite(LED_BUILTIN, HIGH);
  }
}

// 音量調整用（2倍にする例）
void amplifyAudio(int16_t* samples, size_t sampleCount, float gain) {
  for (size_t i = 0; i < sampleCount; i++) {
    int32_t val = samples[i] * gain;
    if (val > 32767) val = 32767;
    if (val < -32768) val = -32768;
    samples[i] = (int16_t)val;
  }
}

bool checkFreeSpace() {
  unsigned long totalBytes = LittleFS.totalBytes();
  unsigned long usedBytes = LittleFS.usedBytes();
  unsigned long freeBytes = totalBytes - usedBytes;
  unsigned long minFreeBytes = MIN_FREE_SPACE_MB * 1024 * 1024;  // Convert MB to bytes

  applog("LittleFS: Total %lu bytes, Used %lu bytes, Free %lu bytes.\r\n", totalBytes, usedBytes, freeBytes);

  if (freeBytes < minFreeBytes) {
    applog("ERROR: Not enough free space on LittleFS. Required: %lu bytes, Available: %lu bytes.\r\n", minFreeBytes, freeBytes);
    return false;
  }
  return true;
}

void setRtcToDefaultTime() {
  applog("Setting RTC to default time: 2025/01/01 00:00:00\n");
  struct tm defaultTime;
  defaultTime.tm_year = 2025 - 1900;  // Year since 1900
  defaultTime.tm_mon = 0;             // Month (0-11, so Jan is 0)
  defaultTime.tm_mday = 1;            // Day of the month (1-31)
  defaultTime.tm_hour = 0;            // Hour (0-23)
  defaultTime.tm_min = 0;             // Minute (0-59)
  defaultTime.tm_sec = 0;             // Second (0-59)
  defaultTime.tm_isdst = -1;          // Daylight Saving Time flag (-1 means unknown)

  time_t t = mktime(&defaultTime);
  struct timeval now = { .tv_sec = t };
  settimeofday(&now, NULL);
  applog("RTC set to default time.\n");
}

// Function to generate a filename based on RTC time
void generateFilenameFromRTC(char* filenameBuffer, size_t bufferSize) {
  struct tm timeinfo;

  applog("Getting time from internal RTC for initial filename...\n");
  if (getValidRtcTime(&timeinfo)) {
    char time_buf[64];
    strftime(time_buf, sizeof(time_buf), "%A, %B %d %Y %H:%M:%S", &timeinfo);
    applog("Current time from RTC: %s\n", time_buf);
    strftime(filenameBuffer, bufferSize, "/R%Y-%m-%d-%H-%M-%S.wav", &timeinfo);
    applog("Generated filename (RTC): %s\r\n", filenameBuffer);
  }
}

bool isConnectUSB() {
  return digitalRead(USB_DETECT_PIN) == HIGH;
}

// Helper function to get time from internal RTC and check its validity.
bool getValidRtcTime(struct tm* timeinfo) {
  if (getLocalTime(timeinfo)) {
    if (timeinfo->tm_year > (2000 - 1900)) {  // Check if year is after 2000 (epoch is 1970)
      return true;
    }
  }
  return false;
}

// Helper function to get formatted RTC time string.
bool getFormattedRtcTime(char* buffer, size_t bufferSize) {
  struct tm timeinfo;
  if (getValidRtcTime(&timeinfo)) {
    strftime(buffer, bufferSize, "%m/%d %k:%M:%S", &timeinfo);
    return true;
  } else {
    strncpy(buffer, "RTC Not Set", bufferSize);
    buffer[bufferSize - 1] = '\0';  // Ensure null-termination
    return false;
  }
}

float getLittleFSUsagePercentage() {
  unsigned long totalBytes = LittleFS.totalBytes();
  unsigned long usedBytes = LittleFS.usedBytes();
  return (float)usedBytes / totalBytes * 100.0f;
}

void initLittleFS() {
  applog("init LittleFS...\n");
  if (!LittleFS.begin()) {
    applog("Formatting LittleFS...\n");
    LittleFS.format();
    if (!LittleFS.begin()) {
      applog("Failed to mount LittleFS!\n");
      while (1)
        ;  // do nothing
    }
  }
  applog("LittleFS init.\n");

  float usagePercentage = getLittleFSUsagePercentage();

  unsigned long totalBytes = LittleFS.totalBytes();
  unsigned long usedBytes = LittleFS.usedBytes();
  unsigned long freeBytes = totalBytes - usedBytes;

  applog("LittleFS: Total %lu bytes, Used %lu bytes, Free %lu bytes (%.2f%% used).\r\n", totalBytes, usedBytes, freeBytes, usagePercentage);

  g_audioFileCount = countAudioFiles();  // Call the new function to update file counts
}

// This function is kept for future use, although currently not called from any button press.
void deleteAllRecordings() {
  applog("Deleting all recording files.\n");
  onboard_led(true);

  File root = LittleFS.open("/", "r");
  if (!root) {
    applog("Failed to open root directory\n");
    onboard_led(false);
    return;
  }

  File file = root.openNextFile();
  while (file) {
    if (!file.isDirectory()) {
      const char* filenameCStr = file.name();
      // Check if the filename ends with ".wav"
      if (strlen(filenameCStr) >= 4 && strcmp(filenameCStr + strlen(filenameCStr) - 4, ".wav") == 0) {
        char fullPath[64];  // Assuming max filename length + '/' + null terminator
        snprintf(fullPath, sizeof(fullPath), "/%s", filenameCStr);
        applog("Deleting file: %s\r\n", fullPath);
        if (!LittleFS.remove(fullPath)) {
          applog(" - Failed to delete\n");
        }
      }
    }
    file = root.openNextFile();
  }
  root.close();
  onboard_led(false);
  applog("Finished deleting recording files.\n");
  g_audioFileCount = countAudioFiles();  // Update file counts after all deletions
}

float getBatteryVoltage() {
  int analogValue = analogRead(BATTERY_DIV_PIN);
  float voltage = analogValue * (3.3 / 4095.0); //(0-4095 maps to 0-3.3V with ADC_11db)
  g_currentBatteryVoltage = voltage * BAT_VOL_MULT; // Update global variable
  return g_currentBatteryVoltage; // Always return the (potentially updated) global variable
}

// Function to count audio files and return the count. Updates the global audioFileCount at call site.
int countAudioFiles() {
  int currentAudioFileCount = 0;

  File root = LittleFS.open("/", "r");
  if (!root) {
    applog("Failed to open root directory to count audio files.\n");
    return 0;
  }

  File file = root.openNextFile();
  while (file) {
    if (!file.isDirectory()) {
      const char* filenameCStr = file.name();
      if (strlen(filenameCStr) >= 4 && strcmp(filenameCStr + strlen(filenameCStr) - 4, ".wav") == 0) {
        currentAudioFileCount++;
      }
    }
    file = root.openNextFile();
  }
  root.close();
  //applog("LittleFS contains %d audio files.\r\n", currentAudioFileCount);
  return currentAudioFileCount;
}

// Helper function to parse filename into a tm struct
bool parseFilenameToTm(const char* filename, struct tm* timeinfo) {
  // Expected format: RYYYY-MM-DD-HH-MM-SS.wav (or /RYYYY-MM-DD-HH-MM-SS.wav if full path)
  // Skip the leading 'R' (or '/' and 'R' if full path)
  const char* effective_filename = filename;
  if (filename[0] == '/') { // Handle full path case
    effective_filename = filename + 1;
  }

  if (strlen(effective_filename) < 19 || effective_filename[0] != 'R') { // R + YYYY-MM-DD-HH-MM-SS.wav (19 chars)
    return false; // Invalid format
  }
  const char* date_time_str = effective_filename + 1; // Point to YYYY-MM-DD-HH-MM-SS.wav

  // Use sscanf to parse the components
  int year, month, day, hour, minute, second;
  if (sscanf(date_time_str, "%d-%d-%d-%d-%d-%d.wav",
             &year, &month, &day, &hour, &minute, &second) == 6) {
    timeinfo->tm_year = year - 1900;
    timeinfo->tm_mon = month - 1; // tm_mon is 0-11
    timeinfo->tm_mday = day;
    timeinfo->tm_hour = hour;
    timeinfo->tm_min = minute;
    timeinfo->tm_sec = second;
    timeinfo->tm_isdst = -1; // Not known
    return true;
  }
  return false;
}

// sorted array, maintaining maxFiles limit
int sortedFilenames(char sortedArray[][MAX_FILENAME_LENGTH], int currentCount, int maxLimit, const char* newFilename, bool ascending) {
  int insertIndex = -1;

  // Find the correct position to insert the newFilename, maintaining sorted order
  for (int i = 0; i < currentCount; ++i) {
    if ((ascending && strcmp(newFilename, sortedArray[i]) < 0) || (!ascending && strcmp(newFilename, sortedArray[i]) > 0)) {
      insertIndex = i;
      break;
    }
  }

  if (insertIndex == -1 && currentCount < maxLimit) {
    // If newFilename is older/newer than all existing files but there's still space
    insertIndex = currentCount;
  } else if (insertIndex == -1 && currentCount == maxLimit) {
    // If newFilename is older/newer than all existing files and no space, skip
    return currentCount; // No change in count
  }

  if (insertIndex != -1) {
    // Shift elements to make space for the new file
    // If we are at maxLimit, the last element will be dropped
    for (int i = (currentCount < maxLimit ? currentCount : maxLimit - 1); i > insertIndex; --i) {
      strncpy(sortedArray[i], sortedArray[i - 1], MAX_FILENAME_LENGTH);
      sortedArray[i][MAX_FILENAME_LENGTH - 1] = '\0';
    }
    strncpy(sortedArray[insertIndex], newFilename, MAX_FILENAME_LENGTH);
    sortedArray[insertIndex][MAX_FILENAME_LENGTH - 1] = '\0';
    if (currentCount < maxLimit) {
      currentCount++;
    }
  }
  return currentCount;
}

int getLatestAudioFilenames(char outputArray[][MAX_FILENAME_LENGTH], int maxFiles, bool ascending) {
  char tempFiles[maxFiles][MAX_FILENAME_LENGTH];
  int currentFileCount = 0;

  for (int i = 0; i < maxFiles; ++i) {
    tempFiles[i][0] = '\0'; // Initialize filenames to empty string
  }

  File root = LittleFS.open("/", "r");
  if (!root) {
    applog("Failed to open directory\n");
    return 0;
  }

  File file = root.openNextFile();
  while (file) {
    if (!file.isDirectory() && strstr(file.name(), ".wav") != nullptr) {
      const char* filenameCStr = file.name();
      currentFileCount = sortedFilenames(tempFiles, currentFileCount, maxFiles, filenameCStr, ascending);
    }
    file = root.openNextFile();
  }
  root.close();

  for (int i = 0; i < currentFileCount; ++i) {
    strncpy(outputArray[i], tempFiles[i], MAX_FILENAME_LENGTH);
    outputArray[i][MAX_FILENAME_LENGTH - 1] = '\0';
  }

  return currentFileCount;
}

void execUpload() {
  applog("try to upload all WAV files.\n");
  
  g_audioFileCount = countAudioFiles(); // Update file count before array declaration
  if (g_audioFileCount == 0) {
    applog("No audio files to upload.\n");
    return;
  }

  // --- New: Authentication check before starting file uploads ---
  if (!checkAuthentication(HS_HOST, HS_PORT, HS_PATH, HS_USER, HS_PASS)) {
    applog("Authentication failed. Aborting file uploads.\n");
    updateDisplay("AUTH ERR"); // Display persistent authentication error
    return; // Abort execUpload
  }
  // --- End New ---

  char wavFilesToProcess[g_audioFileCount][MAX_FILENAME_LENGTH]; 
  int numFiles = getLatestAudioFilenames(wavFilesToProcess, g_audioFileCount, true); // Get all files, oldest first for upload

  applog("Found %d WAV files to process for upload.\r\n", numFiles);

  // Phase 2: Process collected WAV files (check size, upload, delete)
  for (int i = 0; i < numFiles; ++i) {
    char fullPath[MAX_FILENAME_LENGTH + 1]; // +1 for '/' character
    snprintf(fullPath, sizeof(fullPath), "/%s", wavFilesToProcess[i]);
    const char* currentFilename = fullPath;
    applog("Processing file for upload: %s\r\n", currentFilename);

    File audioFileForSize = LittleFS.open(currentFilename, FILE_READ);
    if (!audioFileForSize) {
      applog("ERROR: Could not open file %s to check size. Skipping.\r\n", currentFilename);
      continue;
    }

    size_t fileSize = audioFileForSize.size();
    audioFileForSize.close(); // Close the file after getting its size

    if (fileSize < MIN_AUDIO_FILE_SIZE_BYTES) {
      applog("File %s is too short (size: %u bytes). Deleting from LittleFS.\r\n", currentFilename, fileSize);
      if (!LittleFS.remove(currentFilename)) {
        applog("Failed to delete short file %s from LittleFS.\r\n", currentFilename);
      }
      g_audioFileCount = countAudioFiles(); // Update file counts after deletion
      continue;
    }

    if (uploadAudioFileViaHTTP(currentFilename, HS_HOST, HS_PORT, HS_PATH, HS_USER, HS_PASS)) {
      applog("File %s uploaded successfully. Deleting from LittleFS.\r\n", currentFilename);
      if (!LittleFS.remove(currentFilename)) {
        applog("Failed to delete file %s from LittleFS.\r\n", currentFilename);
      }
    } else {
      applog("Failed to upload file %s. Stopping further uploads.\r\n", currentFilename);
      updateDisplay("UPLOAD ERR"); // Display error on LCD
      break; // Exit the loop immediately
    }
    g_audioFileCount = countAudioFiles(); // Update file counts after deletion/upload attempt
  }
  applog("Finished processing WAV files for upload.\n");
}