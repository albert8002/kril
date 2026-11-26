# CORE_DUMP

Rare-answer trivia (concept after krillion.io) in an amber-CRT, horror-terminal skin.
Kotlin Multiplatform + Compose Multiplatform → Android APK and iOS IPA.

## Play
- A prompt appears ("Name a programming language"). Type any valid answer.
- Rarer answers score more. 1 point = 10 frames of stack depth.

| Tier | Points | Meaning |
|---|---|---|
| HELLO_WORLD | 10 | everyone writes this first |
| COPYPASTA | 15 | the "clever" pick everyone uses |
| LEGACY | 30 | known, not top-of-mind |
| MEMORY_LEAK | 60 | slipped past almost everyone |
| HEISENBUG | 85 | vanishes when anyone looks |
| ZERO_DAY | 100 | the one hidden gem per prompt |
| SEGFAULT / TIMEOUT | 0 | invalid / no answer |

Ranks (average points per prompt): INTERN → JUNIOR DEV → ON-CALL → NIGHT SHIFT → DEBUGGER → EXORCIST → ROOT.

## Features
- Settings: prompts per run (1–50), time per prompt (10–90 s or ∞), difficulty (EASY / NORMAL / HARD / NIGHTMARE), topic filter.
- End screen: every round expands to show the answers that would have scored more.
- Question sources, all prefetched + ranked at boot, cached 7 days:
  - 52 bundled hand-ranked lists (~5,000 answers, offline)
  - Wikidata SPARQL (42 live templates; rarity = Wikipedia language editions)
  - Wikipedia "List of …" scraping (19 pages; rarity = 30-day page views)
  - any remote JSON pack URL (see `packs/`)
- Answer lookup: hash map of normalised keys (O(1)) + symmetric-delete fuzzy index for typos.
- 1v1 over local Wi-Fi / hotspot (Android ↔ iPhone): host shows a QR sigil, guest scans it.

## Build
Push to `main` → GitHub Actions builds `CoreDump.apk` and `CoreDump-unsigned.ipa` and attaches both to a Release.
- APK: release build signed with the debug key → installs directly.
- IPA: unsigned → sideload with AltStore / SideStore / Sideloadly (they sign with your Apple ID).

Local: `./gradlew :composeApp:assembleRelease` (Android); `cd iosApp && xcodegen generate` then open in Xcode (iOS).

## Web app
The same game in the browser: https://albert8002.github.io/kril/ (built by CI on every push, branch `gh-pages`).
- First time only: Settings → Pages → Build and deployment → Source "Deploy from a branch" → `gh-pages` / `/ (root)`.
- Needs a current browser (Chrome / Edge 119+, Firefox 120+, Safari 18.2+). Online 1v1, ranked and MELTDOWN work;
  direct Wi-Fi does not (browsers have no raw sockets). Scanning uses the camera (allow it when asked).
- Local: `./gradlew :composeApp:wasmJsBrowserDevelopmentRun`.

## Accounts (optional)
Sign-in with e-mail + password (Supabase). An account syncs settings, looks, run history and friends between
phones and the web app, lets friends be added by name, and keeps the ranked rating on the server: a match between
two accounts is rated only when both players report the same result (and at most 10 rated games per pair per day).

Set up once:
1. Make a free project at https://supabase.com.
2. SQL Editor → New query → paste `supabase/schema.sql` → Run.
3. Authentication → URL Configuration: Site URL `https://albert8002.github.io/kril/` (e-mail links open the web app).
4. Project Settings → API: copy the Project URL and the `anon` / publishable key, then either
   - put them in `composeApp/src/commonMain/composeResources/files/cloud.json`, or
   - add repository variables `SUPABASE_URL` and `SUPABASE_ANON_KEY` (Settings → Secrets and variables → Actions → Variables).
   The key is public by design; what each account may read or write is enforced by the database rules in the script.
