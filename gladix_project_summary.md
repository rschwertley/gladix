# Gladix Project Summary

## What is Gladix?
Gladix is a personal Android music player fork of [Echo](https://github.com/brahmkshatriya/echo) by brahmkshatriya. It bundles the Deezer extension directly and adds significant Android Auto support, stability fixes, and UX improvements.

- **Package:** `dev.rschwertley.gladix`
- **Repo:** github.com/rschwertley/echo
- **Project path:** `C:\Users\rschw\StudioProjects\echo`
- **Deezer extension:** bundled as `:deezer-extension` Gradle module
- **Firebase:** Crashlytics enabled, `google-services.json` in `app/` (gitignored)

---

## Development Environment
- Android Studio Panda 3 (2025.3.3)
- JDK 17, Windows 11
- Claude Code at project root
- DHU: `C:\Users\rschw\AppData\Local\Android\Sdk\extras\google\auto\desktop-head-unit.exe`
- ADB commands: `adb forward tcp:5277 tcp:5277` then DHU
- Wireless ADB: `adb tcpip 5555` → `adb connect 192.168.x.x:5555`

---

## Key Files Modified

### Android Auto
- `app/.../playback/AndroidAutoCallback.kt` — main Auto browse/search/playback
- `app/.../playback/ShufflePlayer.kt` — windowed timeline for Bluetooth queue
- `app/.../playback/PlayerCommands.kt` — custom action buttons (Shuffle, Repeat, Like)

### Playback
- `app/.../playback/PlayerService.kt` — foreground service, ExoPlayer setup
- `app/.../playback/PlayerCallback.kt` — Media3 session callbacks, resumption
- `app/.../playback/PlayerEventListener.kt` — error handling, auto-skip circuit breaker
- `app/.../playback/source/StreamableMediaSource.kt` — stream loading, race condition fixes
- `app/.../playback/ResumptionUtils.kt` — queue/index recovery from SharedPrefs
- `app/.../playback/listener/AudioFocusListener.kt` — REMOVED (was conflicting with ExoPlayer)

### UI
- `app/.../ui/media/more/MediaMoreBottomSheet.kt` — context menu, unsave dialog
- `app/.../ui/media/more/MoreButton.kt` — dismissOnClick parameter
- `app/.../ui/media/MediaHeaderAdapter.kt` — bookmark icon, unsave confirmation
- `app/.../ui/player/PlayerFragment.kt` — fullscreen album art, Ken Burns, back gesture
- `app/.../ui/player/more/PlayerMoreFragment.kt` — Lyrics/Queue tab fix
- `app/.../ui/player/PlayerViewModel.kt` — STATE_IDLE prepare() fix
- `app/.../ui/common/UiViewModel.kt` — nav bar double-tap fix, playerBgVisible

### Audio Effects
- `app/.../playback/CrossfadeAudioProcessor.kt` — volume dip fade between tracks
- `app/.../playback/EffectsListener.kt` — fade scheduling

### History
- `app/.../history/db/HistoryDatabase.kt` — Room DB version 2
- `app/.../history/db/HistoryEntity.kt` — track history entity
- `app/.../history/HistoryRepository.kt`
- `app/.../ui/history/HistoryFragment.kt`

### Deezer Extension
- `deezer-extension/ext/.../clients/DeezerArtistClient.kt` — artist album/track pagination
- `deezer-extension/ext/.../api/DeezerArtist.kt` — REST API calls
- `deezer-extension/ext/.../DeezerParser.kt` — REST API parsers

---

## Completed Fixes & Features

### Android Auto
- ✅ Browse tabs (Home/Search/Library) loading correctly
- ✅ Artist drill-down showing correct content
- ✅ Search returning real Deezer results
- ✅ Voice search via Google Assistant
- ✅ Queue view in Now Playing
- ✅ Shuffle + Repeat buttons in Now Playing controls
- ✅ Auto-pause on disconnect
- ✅ Playback resumption on reconnect
- ✅ Auto-skip unavailable/region-locked tracks (3-skip circuit breaker)
- ✅ Disabled extensions filtered from Auto browse
- ✅ Proper error messages instead of silent failures
- ✅ Fixed `isRecent` guard preventing notification shade from clearing Auto caches
- ✅ Fixed package name guard so only gearhead browser triggers cache clear
- ✅ Windowed timeline (50 items) for Bluetooth queue — prevents BadParcelableException
- ✅ `getCurrentMediaItemIndex()` override in ShufflePlayer — fixes PlayerInfo$Builder crash from volume buttons

### Playback & Stability
- ✅ Removed conflicting AudioFocusListener — ExoPlayer built-in focus now exclusive
- ✅ Fixed StreamableMediaSource race conditions (3 bugs): released flag, loadJob cancellation, createPeriod guard
- ✅ Fixed null/empty sources now throw TrackUnavailableException instead of buffering forever
- ✅ Fixed double setMediaItems in onPlaybackResumption (second track hang on BT reconnect)
- ✅ Fixed player stuck in STATE_IDLE: prepare() before play() in PlayerViewModel
- ✅ Fixed circuit breaker leaving player in bad state: player.pause() added
- ✅ Fixed recreate() infinite loop with dynamic player colors (lastPlayerAccentColor guard)
- ✅ Fixed ForegroundServiceStartNotAllowedException: startForegroundCompat() in onCreate()
- ✅ Fixed ForegroundServiceStartNotAllowedException in onUpdateNotification()
- ✅ Fixed PlayerInfo$Builder crash: index clamping in ResumptionUtils.recoverPlaylist()
- ✅ Fixed Bluetooth queue BadParcelableException: WindowedTimeline in ShufflePlayer
- ✅ Fixed TransactionTooLargeException: MediaMoreBottomSheet uses staging ViewModel
- ✅ Fixed Room HistoryDatabase version 1→2
- ✅ Fixed LyricsFragment ClassCastException
- ✅ Fixed PlayerMoreFragment Lyrics/Queue tab overlap (isChecked guard)
- ✅ Fixed nav bar single-tap misregistered as double-tap (hasUserInteracted guard)

### Memory & Thread Safety
- ✅ Fixed WeakHashMap GC bug → Collections.synchronizedMap(LinkedHashMap()) LRU bounded at 500
- ✅ Added SupervisorJob to App.kt, ExtensionLoader.kt, Downloader.kt scopes
- ✅ Fixed NetworkCallback never unregistered
- ✅ Fixed BroadcastReceiver never unregistered in AppRepository
- ✅ Fixed SharedPreferences listener leak in PlayerRadio
- ✅ Fixed WeakHashMap with boxed Long keys in Downloader
- ✅ Fixed CancellationException swallowing in ContextUtils.observe
- ✅ Fixed ControllerHelper singleton unsynchronized mutable state

### UI/UX
- ✅ Compact Spotify-style context menus (icon left, text right, larger text/icons)
- ✅ Shuffle Play in playlist/album context menus
- ✅ Voice search microphone in all search bars
- ✅ Listening history with clock icon on home screen
- ✅ Unsave playlist confirmation dialog (both context menu and bookmark icon)
- ✅ Bookmark icon hidden for owned playlists
- ✅ Fullscreen album art with Ken Burns pan/zoom animation
- ✅ Fixed fullscreen album art back gesture (single swipe)
- ✅ Ken Burns visibility fix (MAX_WIDTH 256→512, radius 12→8)
- ✅ Splash screen animation (Gladix bolt, ring spin, draw-in effect)
- ✅ Faster app startup (ExtensionLoader deferred to IO thread)
- ✅ Splash screen duration 500ms

### Audio
- ✅ Track Fade (volume dip) configurable 1-12s, accessible from Audio Effects sheet
- ✅ Default off

### Deezer Extension
- ✅ Artist albums paginated via REST API (full catalog)
- ✅ Artist top tracks paginated via REST API
- ✅ Related artists paginated
- ✅ handleArlExpiration() in saveToLibrary()

### Branding
- ✅ App name: Gladix
- ✅ Package applicationId: dev.rschwertley.gladix
- ✅ Custom Gladix lightning bolt icon
- ✅ Splash screen with Gladix animation
- ✅ Tagline: "Your music, your way."
- ✅ All Echo string references updated to Gladix
- ✅ Bengali locale app name fixed
- ✅ Built-in extension authors updated
- ✅ About page: proper credits for brahmkshatriya and LuftVerbot
- ✅ About page: removed Discord/Telegram/Ko-fi, updated GitHub links
- ✅ Wiki link points to Gladix GitHub README
- ✅ README updated

### Crash Reporting
- ✅ Firebase Crashlytics integrated
- ✅ Non-fatal errors forwarded via throwFlow

---

## Outstanding Issues

### Active Bugs (needs investigation)
- 🔲 Library shows "No Items" ~50% of Android Auto sessions — intermittent, likely timing/race condition at connection
- 🔲 Random mid-song pauses in Android Auto — cause unknown, needs logcat capture

### Monitoring
- 🔲 PlayerInfo$Builder crash — monitoring Firebase after ShufflePlayer fix
- 🔲 Intermittent play button spinning — monitoring after StreamableMediaSource fixes

### Wishlist
- 🔲 True crossfade (dual MediaCodecAudioRenderer approach — significant project)
- 🔲 Tidal extension (paused, has unresolved issues)

---

## Useful Debug Commands

```bash
# Android Auto logs
adb logcat -s GladixAuto

# Playback logs  
adb logcat -s GladixPlayback

# All crashes
adb logcat -s AndroidRuntime | findstr "FATAL"

# Dropbox crash history
adb shell dumpsys dropbox --print > C:\Users\rschw\dropbox.txt

# DHU setup
adb forward tcp:5277 tcp:5277
C:\Users\rschw\AppData\Local\Android\Sdk\extras\google\auto\desktop-head-unit.exe

# Wireless ADB
adb tcpip 5555
adb connect 192.168.x.x:5555

# Git sync
cd C:\Users\rschw\StudioProjects\echo
git add -A
git commit -m "message"
git push
```

---

## Key Architecture Notes
- Android Auto: `AndroidAutoCallback.kt` (MediaLibrarySession.Callback)
- Audio: ExoPlayer with `handleAudioFocus=true`, AudioFocusListener removed
- Extensions: `ExtensionLoader.kt` with hardcoded lazy `DeezerExtension()`
- History: separate Room DB "history-db" version 2
- Crash logging: Firebase Crashlytics + GladixAuto/GladixPlayback/GladixUI Log.d tags
- Bluetooth queue: `WindowedTimeline` inner class in `ShufflePlayer.kt`, QUEUE_WINDOW_SIZE=50