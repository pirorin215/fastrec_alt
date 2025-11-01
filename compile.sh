sh calculate_partition_size.sh

#arduino-cli cache clean

# Run arduino-cli compile and capture output, including time
COMPILE_COMMAND="arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32S3:JTAGAdapter=builtin fastrec_alt.ino"
echo $COMPILE_COMMAND
TIME_AND_COMPILE_OUTPUT=$( { time $COMPILE_COMMAND ; } 2>&1 )
COMPILE_EXIT_CODE=$?

# Separate compile output from time output
COMPILE_OUTPUT=$(echo "$TIME_AND_COMPILE_OUTPUT" | sed '/^real/d; /^user/d; /^sys/d')
TIME_OUTPUT=$(echo "$TIME_AND_COMPILE_OUTPUT" | grep -E '^(real|user|sys)')

echo "$COMPILE_OUTPUT"

if [ $COMPILE_EXIT_CODE -ne 0 ]; then
    echo "Arduino compilation failed."
    exit $COMPILE_EXIT_CODE
fi

# Extract firmware size from compile output
FIRMWARE_SIZE_BYTES=$(echo "$COMPILE_OUTPUT" | grep "スケッチが.*バイト" | awk -F'スケッチが' '{print $2}' | awk -F'バイト' '{print $1}')

# Extract app0 size from partitions.csv
APP0_SIZE_KB=$(grep "app0," partitions.csv | awk -F',' '{print $5}' | sed 's/K//' | tr -d ' ')
APP0_SIZE_BYTES=$((APP0_SIZE_KB * 1024))

if [ -z "$FIRMWARE_SIZE_BYTES" ] || [ -z "$APP0_SIZE_BYTES" ]; then
    echo "Could not determine firmware size or app0 partition size."
    exit 1
fi

# Calculate firmware size in KB (integer)
FIRMWARE_SIZE_KB=$(echo "scale=0; $FIRMWARE_SIZE_BYTES / 1024" | bc)

# Calculate percentage (integer)
USAGE_PERCENT_INT=$(echo "scale=0; ($FIRMWARE_SIZE_BYTES * 100) / $APP0_SIZE_BYTES" | bc)

echo ""
echo "--- ファームウェアサイズ分析 ---"
echo "ファームウェアサイズ: ${FIRMWARE_SIZE_BYTES} バイト (${FIRMWARE_SIZE_KB}KB)"
echo "App0パーティションサイズ: ${APP0_SIZE_BYTES} バイト (${APP0_SIZE_KB}KB)"
echo "使用率: ${USAGE_PERCENT_INT}%"
echo "----------------------------"

echo "$TIME_OUTPUT"
