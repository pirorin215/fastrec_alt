#ifndef FASTREC_INO_H
#define FASTREC_INO_H

#include "FS.h"
#include "LittleFS.h"
#include <NimBLEDevice.h>
#include <vector> // VectorCheck:allows
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"

// GPIO settings
#define REC_BUTTON_GPIO    GPIO_NUM_1

#define MOTOR_GPIO         GPIO_NUM_3
#define USB_DETECT_PIN     GPIO_NUM_4
#define BATTERY_DIV_PIN    GPIO_NUM_5
#define I2S_BCLK_PIN       GPIO_NUM_7
#define I2S_DOUT_PIN       GPIO_NUM_8
#define I2S_LRCK_PIN       GPIO_NUM_9
#define LCD_SDA_GPIO       GPIO_NUM_43
#define LCD_SCL_GPIO       GPIO_NUM_44

// --- Configuration Constants ---
// Serial
const long SERIAL_BAUD_RATE = 115200;
const long SERIAL_TIMEOUT_MS = 5000;  // 5 seconds timeout for serial connection

// Power/Battery
unsigned long DEEP_SLEEP_DELAY_MS = 15000;
unsigned long DEEP_SLEEP_CYCLE_MINUTES = 60; // Default 60 minutes
unsigned long DEEP_SLEEP_CYCLE_MS = DEEP_SLEEP_CYCLE_MINUTES * 60 * 1000; // Calculated from minutes
float BAT_VOL_MIN = 3.0f;
float BAT_VOL_MULT = 2.1f;

// Audio/Recording
int REC_MAX_S = 20;
int REC_MIN_S = 2;
int I2S_SAMPLE_RATE = 8000;
float AUDIO_GAIN = 8.0f;
size_t MIN_AUDIO_FILE_SIZE_BYTES; // Calculated based on REC_MIN_S
unsigned long MAX_REC_DURATION_MS = REC_MAX_S * 1000;  // Max recording duration in milliseconds
const size_t I2S_BUFFER_SIZE = 1024; // Defined here as it's a constant

// LittleFS
const unsigned long MIN_FREE_SPACE_MB = 1;  // Minimum 1MB free space required on LittleFS

// Logging
const char* LOG_FILE_0 = "/log.0.txt";
const char* LOG_FILE_1 = "/log.1.txt";
const unsigned long MAX_LOG_SIZE = 100 * 1024; // 100KB

// Vibration
unsigned long VIBRA_STARTUP_MS = 500;
unsigned long VIBRA_REC_START_MS = 300;
unsigned long VIBRA_REC_STOP_MS = 300;
bool VIBRA = true;

// Other Timings/Debounce
const unsigned long STATE_CHANGE_DEBOUNCE_MS = 200; // Debounce time for state changes

// --- End Configuration Constants ---

// App States
#define X_APP_STATES(X) \
  X(INIT,   "INIT"), \
  X(IDLE,   "IDLE"), \
  X(REC,    "REC"), \
  X(DSLEEP, "DSLEEP"), \
  X(SETUP,  "SETUP")
// このコメントを消したり、ここにコードを書いたりしてはいけない

#define APP_STATE_ENUM(name, str) name
enum AppState {
  X_APP_STATES(APP_STATE_ENUM)
};

#define APP_STATE_STRING(name, str) str
const char* appStateStrings[] = {
  X_APP_STATES(APP_STATE_STRING)
};

const int MAX_FILENAME_LENGTH = 32;

// --- WAV Header Structure ---
typedef struct {
  char riff[4];            // "RIFF"
  uint32_t chunkSize;
  char wave[4];            // "WAVE"
  char fmt[4];             // "fmt "
  uint32_t subchunk1Size;
  uint16_t audioFormat;
  uint16_t numChannels;
  uint32_t sampleRate;
  uint32_t byteRate;
  uint16_t blockAlign;
  uint16_t bitsPerSample;
  char data[4];            // "data"
  uint32_t subchunk2Size;
} WavHeader;

// --- Global Variables (Declarations only, definitions will remain in .ino) ---

// fastrec_alt

RTC_DATA_ATTR bool LOG_AT_BOOT = false;

bool g_enable_logging = true;
volatile AppState g_currentAppState;
unsigned long g_boot_time_ms = 0;
unsigned long g_lastActivityTime;
float g_currentBatteryVoltage;
volatile unsigned long g_scheduledStopTimeMillis;
NimBLEServer* pBLEServer; // Global pointer to the BLE server instance

// audio
std::vector<int16_t> g_audio_buffer; // VectorCheck:allows
volatile size_t g_buffer_head;
volatile size_t g_buffer_tail;
SemaphoreHandle_t g_buffer_mutex;
TaskHandle_t g_i2s_reader_task_handle;
volatile bool g_is_buffering;

File g_audioFile;
char g_audio_filename[64];
int g_audioFileCount;
uint32_t g_totalBytesRecorded = 0;

// ble setting
volatile bool g_start_file_transfer = false;
std::string g_file_to_transfer_name;

// Function Prototypes ---

#endif // FASTREC_INO_H
