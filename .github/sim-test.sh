#!/usr/bin/env bash
# iOS simulator: runs the in-app self-test and screenshots each "SELFTEST SHOT"; collects crash reports.
set -u
OUT="$GITHUB_WORKSPACE/sim-out"; mkdir -p "$OUT/shots"
log() { echo "$(date +%T) $*" | tee -a "$OUT/summary.txt"; }
APP=$(find build/sim/Build/Products -maxdepth 2 -name "CoreDump.app" | head -1)
log "app: $APP"
DEV=$(xcrun simctl list devices available -j | python3 -c "
import json,sys
d=json.load(sys.stdin)['devices']
c=[x['udid'] for k,v in sorted(d.items(), reverse=True) if 'iOS' in k for x in v if x['name'].startswith('iPhone')]
print(c[0] if c else '')")
log "device: $DEV"
[ -n "$DEV" ] || { log "no simulator"; exit 0; }
xcrun simctl boot "$DEV" 2>/dev/null; xcrun simctl bootstatus "$DEV" -b >/dev/null 2>&1
xcrun simctl install "$DEV" "$APP" && log "installed"
xcrun simctl launch --console-pty "$DEV" app.coredump.ios -selftest > "$OUT/console.txt" 2>&1 &
LP=$!
seen=0
end=$((SECONDS + 1100))
while [ $SECONDS -lt $end ]; do
  sleep 0.4
  shots=($(grep -o "SELFTEST SHOT [A-Za-z0-9_]*" "$OUT/console.txt" | awk '{print $3}'))
  while [ $seen -lt ${#shots[@]} ]; do
    xcrun simctl io "$DEV" screenshot "$OUT/shots/$(printf %02d $seen)_${shots[$seen]}.png" >/dev/null 2>&1
    seen=$((seen + 1))
  done
  if grep -q "SELFTEST DONE" "$OUT/console.txt"; then log "self-test finished"; break; fi
  if ! kill -0 $LP 2>/dev/null; then log "APP PROCESS ENDED"; break; fi
done
sleep 2
grep "SELFTEST\|CRASHLOG" "$OUT/console.txt" > "$OUT/selftest.txt"
mkdir -p "$OUT/reports"
cp ~/Library/Logs/DiagnosticReports/*CoreDump* "$OUT/reports/" 2>/dev/null || true
DATA=$(xcrun simctl get_app_container "$DEV" app.coredump.ios data 2>/dev/null)
[ -n "$DATA" ] && cp "$DATA/Documents/crash_"*.txt "$OUT/" 2>/dev/null || true
kill $LP 2>/dev/null
exit 0
