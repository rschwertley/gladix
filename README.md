# Gladix: Music Player

A personal fork of [Echo](https://github.com/brahmkshatriya/echo) — an extension-based music player for Android — with enhanced Android Auto support, bundled Deezer integration, and various stability and UX improvements.

> **Note:** Gladix is a personal project and is not affiliated with any content providers, streaming services, or the original Echo developers. This application hosts zero content. The user is responsible for managing their own sources and complying with applicable terms of service.

---

## What's Different from Echo

### Android Auto
- Full browse tree support — Home, Search, and Library tabs load correctly per extension
- Search returns real results from Deezer
- Voice search support via Google Assistant ("Hey Google, play X on Gladix")
- Queue view in Now Playing screen
- Shuffle and Repeat buttons in Now Playing controls
- Auto-pause when disconnecting from car
- Playback resumption when reconnecting
- Auto-skip unavailable/region-locked tracks with circuit breaker
- Proper error messages instead of silent failures
- Fixed browse breaking when playback initiated from phone

### Bundled Deezer Extension
- Deezer is included out of the box — no separate extension install required
- Additional extensions (Spotify, Tidal, etc.) can still be installed on top
- Paginated artist albums, top tracks, and related artists (full catalog, not just first page)

### Audio
- Track Fade — configurable volume fade between tracks (1–12 seconds)
- Accessible directly from Audio Effects sheet while listening
- Fixed audio focus — other players pause when Gladix starts playing

### UI/UX
- Compact Spotify-style context menus (icon left, text right)
- Shuffle Play option in playlist/album context menus
- Voice search microphone in all search bars
- Listening history — clock icon on home screen shows recently played tracks
- Confirmation dialog before removing a playlist from library
- Bookmark icon hidden for playlists you own (can't be unsaved)
- Fullscreen album art with Ken Burns pan/zoom animation
- Fixed Lyrics/Queue tab overlap in player
- Fixed fullscreen album art requiring two back gestures to dismiss
- Smooth player animation on first load

### Stability & Performance
- Fixed audio focus conflicts causing no audio output on Bluetooth
- Fixed StreamableMediaSource race conditions causing play button spinning
- Fixed Bluetooth queue size crash on large playlists
- Fixed foreground service startup timing crashes
- Fixed PlayerInfo$Builder crash triggered by Bluetooth volume buttons
- Fixed second track hang after Bluetooth reconnect
- Fixed recreate() infinite loop with dynamic player colors
- Fixed WeakHashMap GC bug causing intermittent empty playlists in Android Auto
- Replaced unsafe concurrent map access with thread-safe alternatives
- Fixed coroutine scope cancellation issues across multiple services
- Fixed Room database schema versioning for listening history
- Auto-skip tracks with null/empty sources (region-locked content)
- Faster app startup (deferred ExtensionLoader initialization)

### Crash Reporting
- Firebase Crashlytics integrated for automatic crash reporting

---

## Installation

Gladix is a personal build and is not distributed on the Play Store. To build from source:

1. Clone this repository
2. Open in Android Studio
3. Build and run on your Android device

To install extensions (Spotify, Tidal, etc.), download the respective `.eapk` files from the Echo community and open them on your device — Gladix will offer to install them.

---

## Credits

Gladix is built on top of [Echo](https://github.com/brahmkshatriya/echo) by [brahmkshatriya](https://github.com/brahmkshatriya). All core architecture, extension system, and base functionality are their work. Please support the original project.

The bundled Deezer extension is based on [echo-deezer-extension](https://github.com/LuftVerbot/echo-deezer-extension) by LuftVerbot.

---

## Disclaimer

Gladix is intended for personal use only. The developer is not liable for any misuse or legal issues arising from its use. This application hosts zero content — all content is sourced from user-configured extensions and external services.
