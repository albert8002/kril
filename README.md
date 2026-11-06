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
