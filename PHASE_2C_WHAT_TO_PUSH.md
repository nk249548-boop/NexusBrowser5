# Phase 2C — Internal Media Player — WHAT TO PUSH

Replace / create EXACTLY these files in your Android Studio project.
Do not modify any other file.

---

## NEW files (create at the path shown)

```
app/src/main/java/com/nexus/browser/player/
    PlaybackPositionRepository.kt   ← persists resume-position & speed per URI
    SleepTimer.kt                   ← countdown sleep timer with Flow-based UI state
```

---

## MODIFIED files (replace entirely)

| File | What changed |
|------|-------------|
| `app/src/main/java/com/nexus/browser/PlayerActivity.kt` | Full Phase 2C rewrite: gesture controls (seek/brightness/volume), double-tap ±10 s, sleep timer, aspect-ratio cycling (FIT/FILL/ZOOM), lock-screen toggle, auto-PiP on Home, speed persistence, position persistence, animated controls overlay |
| `app/src/main/java/com/nexus/browser/NexusMediaSessionService.kt` | Cleaner Phase 2C version: same service architecture, better comments, explicit `buildSessionPendingIntent()` helper |
| `app/src/main/AndroidManifest.xml` | Added `android:autoEnterPictureInPicture="true"` to PlayerActivity (API 31+), added `android:windowSoftInputMode` |
| `app/src/main/res/values/strings.xml` | Added Phase 2C player string resources |
| `app/build.gradle.kts` | Added `media3-datasource:1.4.1` dependency |

---

## Sync & build steps

1. Copy all files above into the correct paths.
2. In Android Studio: **File → Sync Project with Gradle Files**
3. Build: **Build → Make Project** (or `./gradlew assembleDebug`)
4. No new permissions are needed — all are already in the Manifest from Phase 2A/2B.

---

## Feature summary (Phase 2C)

### PlayerActivity
- **MediaController architecture**: connects to `NexusMediaSessionService`; service owns ExoPlayer + MediaSession.
- **Background playback**: audio/video keeps playing after Home press; notification with play/pause/stop.
- **Lockscreen controls**: MediaSession feeds lockscreen + Quick Settings media panel + Bluetooth.
- **Picture-in-Picture**: auto-enters on Home (API 31+) or via toolbar button; PiP overlay shows play/pause/stop.
- **Resume position**: saved to `PlaybackPositionRepository` (SharedPreferences) on `onStop()`; loaded on next launch. Positions < 3 s or within 5 s of the end are reset to 0.
- **Playback speed**: 0.25×–3×; persisted per URI.
- **Sleep timer**: 5/10/15/20/30/45/60/90 min presets; countdown shown in toolbar; pauses playback on expiry.
- **Gesture controls** (video, unlocked):
  - Horizontal swipe → seek (60 s per full-width swipe)
  - Double-tap left → −10 s, double-tap right → +10 s
  - Left-vertical swipe → screen brightness
  - Right-vertical swipe → media volume
- **Seek bar**: live Slider with timestamps; indeterminate bar for unknown durations.
- **Aspect ratio**: FIT / FILL / ZOOM cycling via toolbar icon.
- **Lock**: toolbar lock icon disables all gestures; unlock chip appears at top-right.
- **Audio mode**: no video surface; displays album-art placeholder, track title, and standard transport controls.
- **Fullscreen toggle**: force-landscape or let system auto-rotate.
- **Error overlay**: retry + close buttons on playback failure.
- **Buffering spinner**: shown while ExoPlayer buffers.

### NexusMediaSessionService
- Singleton ExoPlayer + MediaSession owned by foreground service.
- Audio focus: duck on transient loss, pause on full loss, auto-resume.
- Becoming-noisy: pauses on headset unplug.
- Direct intent commands: `ACTION_PLAY`, `ACTION_PAUSE`, `ACTION_STOP`, `ACTION_SEEK_FORWARD`, `ACTION_SEEK_BACKWARD`, `ACTION_NEXT`, `ACTION_PREVIOUS`.
- `onTaskRemoved`: stops service if paused or empty; keeps alive during active playback.

### PlaybackPositionRepository
- Singleton backed by SharedPreferences (`nexus_playback_prefs`).
- LRU eviction at 200 entries.
- Keys: `pos:<uri>` and `spd:<uri>`.

### SleepTimer
- `CountDownTimer`-based; exposes `StateFlow<Long?>` for live countdown in the UI.
- Presets: 5, 10, 15, 20, 30, 45, 60, 90 minutes.
- `formattedRemaining()` returns "MM:SS" string for the toolbar chip.

---

## Play Store compliance notes

- No stream sniffing, no DRM bypass, no HLS-to-MP4 conversion.
- Only local file URIs (FileProvider) and MediaStore content URIs are accepted.
- Foreground service type `mediaPlayback` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission already declared.
- `autoEnterPictureInPicture` is a manifest-only attribute (no runtime permission needed).
