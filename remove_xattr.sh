#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: $0 <filename>"
  exit 1
fi

FILE="$1"
TEMP_FILE="/tmp/$(basename "$FILE").temp.$$"

if [ ! -f "$FILE" ]; then
  echo "Error: File '$FILE' not found."
  exit 1
fi

echo "Removing extended attributes from '$FILE'..."
cat "$FILE" > "$TEMP_FILE"
if [ $? -eq 0 ]; then
  mv "$TEMP_FILE" "$FILE"
  if [ $? -eq 0 ]; then
    echo "Successfully removed extended attributes from '$FILE'."
  else
    echo "Error: Failed to move temporary file to '$FILE'."
    rm -f "$TEMP_FILE"
    exit 1
  fi
else
  echo "Error: Failed to read file '$FILE'."
  rm -f "$TEMP_FILE"
  exit 1
fi

