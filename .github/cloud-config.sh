#!/usr/bin/env bash
# Account server for this build: repository variables SUPABASE_URL and SUPABASE_ANON_KEY (Settings → Secrets and
# variables → Actions → Variables) are written into files/cloud.json. Without them the committed file stays as it is.
set -u
F=composeApp/src/commonMain/composeResources/files/cloud.json
if [ -n "${SUPABASE_URL:-}" ] && [ -n "${SUPABASE_ANON_KEY:-}" ]; then
  python3 - "$F" <<'PY'
import json, os, sys
json.dump({"url": os.environ["SUPABASE_URL"].strip(), "key": os.environ["SUPABASE_ANON_KEY"].strip()}, open(sys.argv[1], "w"), indent=2)
PY
  echo "accounts: on (from repository variables)"
else
  python3 -c "import json;d=json.load(open('$F'));print('accounts:', 'on (committed config)' if d.get('url') else 'off (no server set)')"
fi
