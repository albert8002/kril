#!/usr/bin/env bash
# Web app in headless Chrome: a plain start, then the in-app self-test (?selftest) with a screenshot at every
# "SELFTEST SHOT"; console output and page errors are kept.
set -u
OUT="$GITHUB_WORKSPACE/web-out"; mkdir -p "$OUT/shots"
SITE="$1"
CHROME=$(command -v google-chrome || command -v google-chrome-stable || command -v chromium || command -v chromium-browser)
echo "chrome: $CHROME ($($CHROME --version 2>/dev/null))" | tee "$OUT/summary.txt"
mkdir -p /tmp/pt && (cd /tmp/pt && npm init -y >/dev/null 2>&1 && npm i puppeteer-core@23 >/dev/null 2>&1) || echo "puppeteer install failed" | tee -a "$OUT/summary.txt"
CHROME="$CHROME" timeout 2700 node "$GITHUB_WORKSPACE/.github/web-test.mjs" "$SITE" "$OUT" 2>&1 | tail -n 60 | tee -a "$OUT/summary.txt"
grep "SELFTEST\|CRASHLOG\|PAGEERROR\|STORE \|SCAN " "$OUT/console.txt" > "$OUT/selftest.txt" 2>/dev/null || true
exit 0
