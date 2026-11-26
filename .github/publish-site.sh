#!/usr/bin/env bash
# Publishes the built web app (directory $1) to branch gh-pages: GitHub Pages serves it at
# https://<owner>.github.io/<repo>/ (Settings → Pages → Deploy from a branch → gh-pages, / (root)).
set -u
DIR="$1"; TOKEN="$2"
[ -f "$DIR/index.html" ] || { echo "no site to publish"; exit 1; }
cd "$DIR"
touch .nojekyll
git init -q && git checkout -q -b pages && git add -A
git -c user.name=ci -c user.email=ci@users.noreply.github.com commit -qm "web app ${GITHUB_RUN_NUMBER} (${GITHUB_SHA})"
git push -qf "https://x-access-token:${TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "pages:gh-pages" && echo "published to gh-pages"
# ask Pages to build now (a push made with the workflow's token may not start one by itself)
curl -s -o /dev/null -w "pages build request: %{http_code}\n" -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Accept: application/vnd.github+json" "https://api.github.com/repos/${GITHUB_REPOSITORY}/pages/builds" || true
