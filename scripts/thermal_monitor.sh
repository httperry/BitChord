#!/bin/bash
# BitChord Live Hardware & Thermal Profiling Logger

LOG_FILE="/storage/emulated/0/Download/bitchord_thermal_log.txt"
echo "=== BitChord Continuous Thermal & Hardware Monitor Started $(date) ===" > "$LOG_FILE"

while true; do
  TS=$(date '+%H:%M:%S')
  
  # 1. Temperatures from HAL & Battery
  TEMPS=$(rish -c "dumpsys thermalservice 2>/dev/null | grep -A 4 'Current temperatures from HAL:' | grep -E 'CPU|GPU|BATTERY|SKIN'")
  BATT=$(rish -c "dumpsys battery 2>/dev/null | grep -iE 'temperature|phonetemp'")
  
  CPU_T=$(echo "$TEMPS" | grep "CPU" | head -n 1 | sed -n 's/.*mValue=\([0-9.]*\).*/\1/p')
  GPU_T=$(echo "$TEMPS" | grep "GPU" | head -n 1 | sed -n 's/.*mValue=\([0-9.]*\).*/\1/p')
  SKIN_T=$(echo "$TEMPS" | grep "SKIN" | head -n 1 | sed -n 's/.*mValue=\([0-9.]*\).*/\1/p')
  BATT_RAW=$(echo "$BATT" | grep "temperature:" | awk '{print $2}')
  BATT_T=$(awk "BEGIN {print $BATT_RAW / 10.0}")
  PHONE_RAW=$(echo "$BATT" | grep "PhoneTemp:" | awk '{print $2}')
  PHONE_T=$(awk "BEGIN {print $PHONE_RAW / 10.0}")
  
  # 2. BitChord Process & Top Active Threads
  PID=$(rish -c "pidof com.music.bitchord.opt 2>/dev/null")
  if [ -n "$PID" ]; then
    TOP_THREADS=$(rish -c "top -b -H -p $PID -n 1 -m 8 2>/dev/null" | awk 'NR>7 && $9 > 0.0 {print $(NF-1) ":" $9 "%"}')
    TOP_STR=$(echo $TOP_THREADS | tr '\n' ' ')
    [ -z "$TOP_STR" ] && TOP_STR="idle (0% CPU)"
  else
    TOP_STR="BitChord not running"
  fi
  
  # 3. Active Foreground Window
  FOCUS=$(rish -c "dumpsys window 2>/dev/null | grep 'mCurrentFocus=' | sed 's/.*mCurrentFocus=//' | tr -d '\r'")
  
  # Write formatted log entry
  ENTRY="[$TS] Battery:${BATT_T}°C | Phone:${PHONE_T}°C | CPU:${CPU_T}°C | GPU:${GPU_T}°C | Skin:${SKIN_T}°C | AppThreads:[${TOP_STR}] | Focus:${FOCUS}"
  echo "$ENTRY" >> "$LOG_FILE"
  echo "$ENTRY"
  
  sleep 4
done
