#!/usr/bin/env bash
# Pushes a trimmed build log to branch ci-log-<platform> so failures can be read without the Actions UI.
set -u
PLATFORM="$1"; STATUS="$2"; TOKEN="$3"
LOG="${GITHUB_WORKSPACE}/build.log"
WORK="$(mktemp -d)"
cd "$WORK"
git init -q && git checkout -q -b log
echo "status=${STATUS} run=${GITHUB_RUN_NUMBER} sha=${GITHUB_SHA}" > status.txt
if [ -f "$LOG" ]; then
  grep -nE "^e: |error:|Error:|FAILURE|What went wrong|Unresolved|BUILD FAILED|\*\* BUILD" "$LOG" | head -400 > errors.txt || true
  tail -n 1500 "$LOG" > tail.txt
else
  echo "no build.log (step did not run)" > errors.txt
fi
git add -A
git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm "log ${PLATFORM} ${GITHUB_RUN_NUMBER} ${STATUS}"
git push -qf "https://x-access-token:${TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "log:ci-log-${PLATFORM}" || echo "log push failed"
