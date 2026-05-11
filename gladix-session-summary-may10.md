# Gladix Session Summary — May 10, 2026

## 1. Audio Focus — Inter-App Playback Fix
**Problem:** Gladix was not yielding audio focus to other music players. Both Gladix and another app (Metrolist, Symfonik) would play simultaneously, or the other app would hang for 30-60 seconds before starting.

**Root cause:** Media3's internal `AudioFocusManager` was running in parallel with our custom `AudioFocusListener`. Despite `setAudioAttributes(audioAttributes, false)` in `PlayerService`, Media3's session initialization was calling `setAudioAttributes` on the `ForwardingPlayer` with `handleAudioFocus=true`, re-enabling the internal manager.

**Files changed:**
- `ShufflePlayer.kt` — overrode `setAudioAttributes` to always pass `handleAudioFocus=false`
- `AudioFocusListener.kt` — fixed focus lifecycle: request focus on every resume, abandon on user-initiated pause, abandon on `STATE_IDLE`

---

## 2. Mid-Drive Random Pause Fix
**Problem:** Gladix was pausing randomly during Android Auto sessions, requiring manual play to resume. Frequent, no apparent trigger.

**Root cause (historical):** Android Auto's Bluetooth HFP/SCO renegotiation was triggering `AUDIOFOCUS_LOSS (-1)`. Additionally, `onDisconnected` in `AndroidAutoCallback` was firing an immediate `player.pause()` on every AA session renegotiation — which is not the same as the user leaving the car.

**Research findings:**
- `onDisconnected` is the wrong API — no production app uses it to control playback
- `AUDIOFOCUS_LOSS` in AA is mostly a side effect; AA mediates app switching via MediaSession directly
- `CarConnection` (Jetpack) is the correct API for detecting actual AA projection end
- `BECOMING_NOISY` already handles Bluetooth A2DP disconnect (already set via `setHandleAudioBecomingNoisy(true)`)

**Files changed:**
- `AndroidAutoCallback.kt` — removed `onDisconnected` immediate pause entirely
- `AudioFocusListener.kt` — added `isAndroidAutoConnected` flag; `AUDIOFOCUS_LOSS` ignored entirely when AA is active; `AUDIOFOCUS_LOSS_TRANSIENT` still pauses immediately
- `PlayerService.kt` — added `CarConnection` observer; pauses when AA projection ends while playing
- `libs.versions.toml` / `app/build.gradle.kts` — added `androidx.car.app:app:1.4.0`

---

## 3. Stream Loading Timeouts
**Problem:** Infinite `STATE_BUFFERING` hang on session restore — stuck for 5-10+ minutes with no progress or error.

**Root cause:** Blocking network calls in the stream loading pipeline with no timeouts. A stalled Deezer API call could hold `prepareChildSource()` open indefinitely.

**Files changed:**
- `StreamableLoader.kt` — added `withTimeout(30_000)` around `load()`
- `DeezerApi.kt` — added `callTimeout(25s)` on API clients; excluded audio streaming client to avoid cutting off song downloads
- `Utils.kt` — added `withTimeout(10_000)` on `getContentLength()`

---

## 4. Deezer Quality Fallback & Error Handling
**Problem:** The shared Deezer server (used for FLAC/320kbps) is inherently unstable per the extension's design. Token failures were being mishandled — showing confusing "Login Required" errors to users who were already logged in, and quality fallback was skipping steps.

**Root causes:**
- `ClientException.LoginRequired` was being thrown as a catch-all when all shared server quality levels failed — unrelated to actual login
- License token error was jumping directly from FLAC to 128kbps, skipping 320kbps
- No retry logic for transient server failures
- Region-unavailable and connection reset errors were being reported to Crashlytics as non-fatals

**Files changed:**
- `DeezerTrackClient.kt`:
  - Fixed quality fallback: FLAC → 320 → 128kbps (was FLAC → 128, skipping 320)
  - Replaced `ClientException.LoginRequired` catch-all with `Exception("Track not available on server")`
  - Added 2-attempt retry loop with 2-second backoff in `loadStreamableMedia`
- `PlayerEventListener.kt`:
  - `ClientException.LoginRequired` now only surfaces for genuine user ARL expiry
  - `SocketException` (connection reset, broken pipe) routes to silent skip — not reported to Crashlytics
  - "Track not available after retries" treated as silent skip

---

## 5. ExceptionFragment Crash Fix
**Problem:** App crashed when user tapped "View" on an error toast for a region-unavailable track.

**Root cause:** `ExceptionFragment` called `registerScreenCaptureCallback` which requires `android.permission.DETECT_SCREEN_CAPTURE` — a permission Gladix doesn't have.

**Files changed:**
- `ExceptionFragment.kt` — removed `registerScreenCaptureCallback`, its unregister, and the unused `screenCaptureCallback` property. `FLAG_SECURE` preserved (no permission required).

---

## 6. Code Quality Cleanup
**MediaPlayerCommands deprecations:**
- `PlayerCommands.kt` — replaced 10 deprecated `CommandButton.Builder` and `setIconResId` calls with current Media3 API (`setCustomIconResId`, typed icon constants)

**Unchecked cast warning:**
- `DeezerArtistClient.kt` — fixed unchecked cast of `List<EchoMediaItem>?` to `List<Track>`

---

## Outstanding / Pending
- **Loudness normalization** — implemented but default OFF pending field verification of Deezer `GAIN` field name
- **Spinning track on session restore** — timeouts now in place; should fail cleanly rather than hang indefinitely
- **Mid-drive pauses** — CarConnection fix not yet tested in car

---

## Commits This Session
1. `Fix audio focus: prevent Media3 AudioFocusManager running in parallel with AudioFocusListener`
2. `Fix mid-drive pauses: remove onDisconnected, add CarConnection for proper AA disconnect detection, ignore AUDIOFOCUS_LOSS during AA sessions`
3. `Fix Media3 CommandButton deprecations: update to new Builder API and setCustomIconResId`
4. `Add timeouts to stream loading pipeline: 30s on StreamableLoader, 25s callTimeout on Deezer API, 10s on getContentLength`
5. `Fix Deezer quality fallback: proper FLAC→320→128 stepdown on license errors, retry loop with backoff, correct LoginRequired handling for shared server vs user ARL`
6. `Fix error handling: remove screen capture crash in ExceptionFragment, silence SocketException and connection reset errors in Crashlytics, treat as silent skip`