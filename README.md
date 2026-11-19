# Auto SD Sync

A lightweight, native Android app that monitors folders on internal storage and automatically moves
**completed** files to a portable SD card — safely, with **COPY → VERIFY → DELETE ORIGINAL**.

Built for low-end devices (e.g. Oppo A3s, Android 8.1 / API 27). No root required.

## Features

- Select multiple source folders (WhatsApp Media, Downloads, Telegram, …) with an in-app folder browser.
- Select the SD card destination using Android's Storage Access Framework (SAF) with a **persistent** permission.
- Directory structure is mirrored on the SD card; subdirectories are preserved.
- Files are only moved once their size has been **stable for ~10s** (so still-writing media isn't corrupted).
- **Duplicate names** are handled by adding a suffix: `photo (1).jpg`.
- **Interrupted transfers** are persisted in SQLite and retried on the next run.
- **Verify before delete**: the original is deleted only after the destination copy is verified byte-for-byte.
- Handles unavailable SD cards, insufficient space, and retries failed transfers.
- Minimal dashboard: Start/Pause, monitoring status, storage recovered, transfer history, folder management.
- Foreground service + `FileObserver` + lightweight periodic scanning; low CPU/RAM/battery use.

## Technical notes

- Kotlin, native Android. `minSdk 21`, `targetSdk 27` (sideload-friendly for Android 8.1).
- SAF for SD access; `FileObserver` + periodic scan for change detection.
- SQLite (`SQLiteOpenHelper`) for transfer history and pending operations.
- Files are streamed in 64 KB buffers — never loaded fully into RAM.

> ⚠️ Moved files are no longer visible to their original apps (e.g. WhatsApp media will not appear in
> the WhatsApp gallery after being moved). This is by design.

## Build

```bash
# Requires JDK 17 and the Android SDK (compileSdk 34)
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

## CI

A GitHub Actions workflow (`.github/workflows/build.yml`) builds the release APK on every push and
uploads it as a build artifact and creates a GitHub Release.
