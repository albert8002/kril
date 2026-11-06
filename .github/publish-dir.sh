#!/usr/bin/env bash
# Pushes a directory to branch ci-log-<name> (screenshots, logs).
set -u
NAME="$1"; DIR="$2"; TOKEN="$3"
[ -d "$DIR" ] || { mkdir -p "$DIR"; echo "no output" > "$DIR/summary.txt"; }
cd "$DIR"
echo "run=${GITHUB_RUN_NUMBER} sha=${GITHUB_SHA}" > status.txt
git init -q && git checkout -q -b log && git add -A
git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm "${NAME} ${GITHUB_RUN_NUMBER}"
git push -qf "https://x-access-token:${TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "log:ci-log-${NAME}" || echo "push failed"
