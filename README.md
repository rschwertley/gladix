# Gladix

A personal Android music player — a fork of [Echo](https://github.com/brahmkshatriya/echo) by brahmkshatriya, with the Deezer extension bundled, local-file playback that needs no account, and substantial work on Android Auto, Android TV, playback stability and everyday UX.

> Gladix is a hobby project maintained by one person. It isn't affiliated with Deezer or any other service.

---

## What it is

Echo is an extension-based music player: the app itself knows nothing about any particular service, and extensions supply the content. Gladix keeps that architecture, bundles Deezer and Offline so it works out of the box, and adds the things a daily-driver player needs.

**Package:** `dev.rschwertley.gladix.auto`

---

## What Gladix adds over Echo

### Android Auto
Browse tabs, artist drill-down, real search with voice input, a queue view, shuffle and repeat controls, and auto-pause on disconnect. Unavailable and region-locked tracks are skipped automatically, with a circuit breaker after three consecutive failures so a bad queue can't spin. Errors surface as readable messages instead of silent failures.

### Android TV
A full-screen D-pad player, a mini-player bar, and focus routing that behaves across the nav rail and mixed-span grids. Deezer login on TV works through a pairing code and a companion web page, since the usual login flow isn't usable with a remote.

### Playback and queue
The queue is stored durably and restored exactly — the same track, the same position — across cold starts, process death and app updates. A buffering watchdog retries and then skips a stuck track rather than leaving the player wedged, with a grace window so a slow stream resolution isn't mistaken for a stall.

### History
Listening history with date sections, sort by date, title or artist, filtering by extension, and text or voice search. Tapping an entry plays that track and rebuilds its context — the playlist, album or radio station it came from.

### Interface
Full-screen album art with a Ken Burns pan, a structured Info tab with credits and technical detail, and compact context menus.

---

## Installing

Grab the latest APK from [Releases](https://github.com/rschwertley/gladix/releases). Once installed, the app checks for new versions on its own.

Also available on Google Play, where Play handles updates.

---

## Building

Standard Android Studio project. JDK 17.

```
./gradlew :app:assembleRelease      # APK
./gradlew :app:bundleRelease        # Play bundle
```

Firebase Crashlytics is wired in; `google-services.json` is gitignored, and the build works without it.

---

## Extensions

Extensions are separate APKs loaded at runtime. Two are bundled: **Deezer**, and **Offline**, which plays music already on the device — so the app does something useful straight after install, with no account and no network. Others are installed by the user.

Two build checks protect the extension boundary, and both run on every shipped build:

- **`verifyExtensionAbi`** confirms R8 hasn't renamed or repackaged the classes extensions link against. An R8 change once broke every third-party extension at once, and this catches that at build time rather than in the field.
- **`verifyCleanKotlinOutput`** guards against stale inlined code after a public inline function changes.

---

## Contributing

This is a personal fork and not open to contributions, but it's public — read it, take from it, or fork it if something here is useful. Issues with Echo itself belong upstream.

---

## Credits

Built on [Echo](https://github.com/brahmkshatriya/echo) by [brahmkshatriya](https://github.com/brahmkshatriya). Extension architecture, core playback and much of the interface are theirs.
