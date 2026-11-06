#!/usr/bin/env bash
# Runs the release APK on an emulator: boot, scripted play, monkey rounds. Collects screenshots, logcat crashes, memory.
set -u
OUT="$GITHUB_WORKSPACE/emu-out"; mkdir -p "$OUT/shots"
APK="$GITHUB_WORKSPACE/dist/CoreDump.apk"
PKG=app.coredump
log() { echo "$(date +%T) $*" | tee -a "$OUT/summary.txt"; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png" 2>/dev/null || true; }
alive() { adb shell pidof $PKG >/dev/null 2>&1; }
launch() { adb shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1; }
adb wait-for-device
adb shell wm size >> "$OUT/summary.txt"
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; log "install: $(tail -1 "$OUT/install.txt")"
adb logcat -c
launch; log "launched"
for i in 1 2 3 4 5 6; do sleep 5; shot "boot_$i"; done
alive && log "alive after boot" || log "DEAD after boot"
adb shell dumpsys meminfo $PKG > "$OUT/meminfo_boot.txt" 2>&1
# scripted: custom script hook (taps) if present
if [ -f "$GITHUB_WORKSPACE/.github/emu-script.sh" ]; then source "$GITHUB_WORKSPACE/.github/emu-script.sh"; fi
# monkey rounds (no system keys / app switching)
for seed in 11 22 33 44 55 66; do
  alive || { log "relaunch before seed $seed"; launch; sleep 25; }
  adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --pct-nav 5 --pct-majornav 5 \
     --throttle 100 -s $seed -v 2000 > "$OUT/monkey_$seed.txt" 2>&1
  shot "monkey_$seed"
  if grep -q "CRASH\|NOT RESPONDING" "$OUT/monkey_$seed.txt"; then log "seed $seed: CRASH/ANR reported"; else log "seed $seed: ok"; fi
  alive || log "seed $seed: process dead"
done
adb shell dumpsys meminfo $PKG > "$OUT/meminfo_end.txt" 2>&1
adb logcat -d -v time > "$OUT/logcat_full.txt" 2>&1
grep -n -B2 -A70 "FATAL EXCEPTION\|Fatal signal\|OutOfMemoryError\|ANR in\|Process: app.coredump" "$OUT/logcat_full.txt" | head -1500 > "$OUT/crashes.txt"
grep -c "FATAL EXCEPTION" "$OUT/logcat_full.txt" | xargs -I{} echo "fatal exceptions: {}" >> "$OUT/summary.txt"
tail -n 30000 "$OUT/logcat_full.txt" > "$OUT/logcat.txt"; rm -f "$OUT/logcat_full.txt"
exit 0
