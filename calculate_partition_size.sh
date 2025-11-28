#!/bin/bash
total_bytes=0

while IFS= read -r line; do
    line_trimmed=$(echo "$line" | tr -d '[:space:]') # 全ての空白を除去

    if [[ -z "$line_trimmed" || "${line_trimmed:0:1}" == "#" ]]; then
        continue
    fi

    size_str=$(echo "$line" | cut -d',' -f5)
    size_str=$(echo "$size_str" | tr -d '[:space:]')

    value=0
    if [[ "$size_str" =~ ^([0-9]+)K$ ]]; then
        value=$(( ${BASH_REMATCH[1]} * 1024 ))
    elif [[ "$size_str" =~ ^([0-9]+)M$ ]]; then
        value=$(( ${BASH_REMATCH[1]} * 1024 * 1024 ))
    else
        value=0 # 現在のpartitions.csvでは発生しないはず
    fi
    total_bytes=$((total_bytes + value))
done < "partitions.csv"

echo "Total size: ${total_bytes} bytes"
echo "Total size: $(awk 'BEGIN {printf "%.2f KB", '${total_bytes}' / 1024}')"
echo "Total size: $(awk 'BEGIN {printf "%.2f MB", '${total_bytes}' / (1024 * 1024)}')"
