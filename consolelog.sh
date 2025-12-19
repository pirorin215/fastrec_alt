#!/bin/sh

CAT_ACTIVE=false

while true; do
  DEVICE_PRESENT=false
  if [ -e /dev/cu.usbmodem21201 ]; then
    DEVICE_PRESENT=true
  fi

  UPLOAD_RUNNING=false
  if pgrep -f "arduino-cli upload" > /dev/null; then
    UPLOAD_RUNNING=true
  fi

  if $DEVICE_PRESENT && ! $UPLOAD_RUNNING; then
    # Should be active
    if ! $CAT_ACTIVE; then
      echo "--- cat started at $(date) ---"
      CAT_ACTIVE=true
    fi
    cat /dev/cu.usbmodem21201
  else
    # Should be inactive
    if $CAT_ACTIVE; then
      echo ""
      echo "--- cat stopped at $(date) ---"
      CAT_ACTIVE=false
    fi
    sleep 0.1
  fi
done
