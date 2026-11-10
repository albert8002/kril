#!/usr/bin/env bash
# Android emulator: runs the in-app self-test (every theme, real games, online checks, collection),
# screenshots each "SELFTEST SHOT", then a few monkey rounds. Collects crashes + memory.
set -u
OUT="$GITHUB_WORKSPACE/emu-out"; mkdir -p "$OUT/shots"
APK="$GITHUB_WORKSPACE/dist/CoreDump.apk"
PKG=app.coredump
log() { echo "$(date +%T) $*" | tee -a "$OUT/summary.txt"; }
alive() { adb shell pidof $PKG >/dev/null 2>&1; }
adb wait-for-device
adb shell wm size >> "$OUT/summary.txt"
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; log "install: $(tail -1 "$OUT/install.txt")"
adb logcat -c
adb logcat -v time > "$OUT/logcat_full.txt" 2>&1 &
LC=$!
adb shell am start -W -n $PKG/.MainActivity --ez selftest true >/dev/null 2>&1
log "launched self-test"
seen=0
end=$((SECONDS + 1260))
while [ $SECONDS -lt $end ]; do
  sleep 0.4
  mapfile -t shots < <(grep -o "SELFTEST SHOT [A-Za-z0-9_]*" "$OUT/logcat_full.txt" | awk '{print $3}')
  while [ $seen -lt ${#shots[@]} ]; do
    adb exec-out screencap -p > "$OUT/shots/$(printf %02d $seen)_${shots[$seen]}.png" 2>/dev/null
    seen=$((seen + 1))
  done
  if grep -q "SELFTEST DONE" "$OUT/logcat_full.txt"; then log "self-test finished"; break; fi
  if ! alive; then log "PROCESS DIED during self-test"; sleep 2; break; fi
done
grep "SELFTEST\|CRASHLOG" "$OUT/logcat_full.txt" > "$OUT/selftest.txt"
adb shell dumpsys meminfo $PKG > "$OUT/meminfo_after_selftest.txt" 2>&1
# random abuse
for seed in 11 22 33; do
  alive || { log "relaunch before seed $seed"; adb shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1; sleep 15; }
  adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --pct-nav 5 --pct-majornav 5 \
     --throttle 100 -s $seed -v 1500 > "$OUT/monkey_$seed.txt" 2>&1
  if grep -q "// CRASH: app.coredump" "$OUT/monkey_$seed.txt"; then log "seed $seed: APP CRASH"; else log "seed $seed: ok"; fi
done
adb shell run-as $PKG cat files/crash_last.txt > "$OUT/crash_last.txt" 2>/dev/null || true
adb shell run-as $PKG cat files/crash_notes.txt > "$OUT/crash_notes.txt" 2>/dev/null || true
kill $LC 2>/dev/null
grep -n -B2 -A60 "FATAL EXCEPTION\|Fatal signal\|OutOfMemoryError" "$OUT/logcat_full.txt" | head -1500 > "$OUT/crashes.txt"
echo "fatal exceptions: $(grep -c 'FATAL EXCEPTION' "$OUT/logcat_full.txt")" >> "$OUT/summary.txt"
grep "app.coredump" "$OUT/logcat_full.txt" | grep -c "GC freed" | xargs -I{} echo "gc lines: {}" >> "$OUT/summary.txt"
grep " $PKG\| System.out\|AndroidRuntime" "$OUT/logcat_full.txt" | tail -n 4000 > "$OUT/logcat_app.txt"
rm -f "$OUT/logcat_full.txt"
exit 0
