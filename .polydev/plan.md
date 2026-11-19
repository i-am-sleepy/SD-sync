# Auto SD Sync — Implementation Plan

## Goal
A lightweight native Kotlin Android app (minSdk 21, targetSdk 27) that monitors internal-storage folders and safely moves completed files to an SD card via SAF, using COPY → VERIFY → DELETE ORIGINAL. The APK is built and published by GitHub Actions.

## Key decisions (defaults I'll use unless you object)
- **Build/publish:** GitHub Actions on `ubuntu-latest` builds the release APK and uploads it as an artifact + creates a GitHub Release. (Local JVM is blocked in this sandbox.)
- **targetSdk 27 / minSdk 21**, compileSdk 34 (compile SDK only affects build, not device behavior).
- **Plain `SQLiteOpenHelper`** for history + pending ops (no Room — lighter build).
- **Source folders:** a lightweight in-app directory browser over real file paths (needed so `FileObserver` gets real paths; Android's SAF picker returns content URIs that FileObserver can't watch). SD destination uses **SAF** `ACTION_OPEN_DOCUMENT_TREE` with a persisted URI.
- **Verify by byte-count + length** (fast, low CPU) rather than hashing every file.
- **Stability gate:** a file is only moved once its size is unchanged for ~10s (prevents moving half-written WhatsApp/Telegram media).

## Stages

### 1. Gradle project scaffold
`settings.gradle.kts`, root & app `build.gradle.kts` (AGP 8.7.3, Kotlin 2.0.20, Gradle 8.9), `gradle.properties`, `.gitignore`, `README.md`, `AndroidManifest.xml`, resource files (strings, themes, colors, vector launcher/notification icons).

### 2. Data layer
`DbHelper` (SQLite: `transfers` history + `pending` interrupted ops), `Models` (TransferRecord, FolderConfig, PendingOp), `ConfigStore` (SharedPreferences JSON for folder list + SD URI).

### 3. Storage & SAF utilities
`SdUtils` (persist/restore SD tree URI, resolve `DocumentFile` paths, create nested dirs, free-space checks), `StorageUtils` (size formatting, recursive listing).

### 4. Transfer engine
`TransferEngine`: sequential buffered-stream copy (64KB buffer), duplicate-name suffixing (`name (1).ext`), byte-count verification, delete original only after verify, interrupted-op recovery from SQLite, retry with backoff, insufficient-space / SD-unavailable handling.

### 5. Monitoring
`Monitor`: `FileObserver` on configured source roots (triggers a scan) + lightweight periodic scan fallback; recursive discovery; stability gate; enqueues transfers.

### 6. Foreground service
`TransferService`: ongoing notification, runs Monitor + TransferEngine, exposes status/statistics, Start/Pause control.

### 7. UI
`MainActivity` dashboard (Start/Pause, monitoring status, storage recovered, transfer history list, folder management add/remove/configure), `FolderPickerActivity` (source folder browser), adapters + layouts.

### 8. Boot + battery
`BootReceiver` (optional auto-start on BOOT_COMPLETED), battery-optimization exemption request (Oppo/ColorOS).

### 9. GitHub Actions
`.github/workflows/build.yml`: checkout → setup Java 17 + Gradle → `assembleRelease` → upload APK artifact → create GitHub Release. Trigger on push.

### 10. Push & verify
Push to your repo with the token (Contents:write, Actions:write), confirm the workflow runs, fix any CI errors, confirm the APK artifact/Release is produced.

## Deliverable
A clean, working repo with a downloadable release APK built by GitHub Actions.