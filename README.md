# WatchOCR

An Android app that watches a folder for new screenshots/images, runs each one through Gemini for OCR, translates the extracted text into Traditional Chinese, and explains any idioms or slang it finds.

## Features

- **Folder monitoring**: pick an image folder (e.g. Screenshots) and a foreground service watches its directory with `FileObserver` (inotify). An image is picked up only when its write completes (`CLOSE_WRITE`) or it is moved into the folder (`MOVED_TO`), so half-written files are never uploaded. Transient failures (network, HTTP 429/5xx) are retried a few times in place; permanent ones (invalid API key, unprocessable image) are not. Swiping the app away from the recents screen deliberately stops monitoring; opening the app again resumes it. Clearing the Gemini API key in Settings also stops it (a folder and a key are both required), taking the ongoing notification down with it. Monitoring is deliberately live-only: it reacts to images as they arrive and never scans the folder for what it may have missed, so images added while it isn't running (after a reboot, after the app was swiped away, or after the system killed the process) are skipped — import those manually if needed.
- **Manual import**: pick a single image from the History tab at any time, independent of the watched folder.
- **OCR + translation + analysis**: each image is sent to the Gemini API, which returns the extracted text, a Traditional Chinese translation, and explanations for any idioms/slang — including a furigana (振り仮名) reading when an expression contains kanji — via a structured JSON response schema.
- **History**: a scrollable list of past results with the source thumbnail, timestamp, extracted text (tap to copy), translation, and idiom/slang analysis. Newly arrived results automatically scroll into view.
- **Local persistence**: OCR results and images are stored on-device (Room database + app-private file storage); nothing is uploaded except the image data sent to the Gemini API for processing. History can be auto-deleted after 1/7/30 days (default: kept forever) or cleared immediately from Settings.

## Requirements

- A Gemini API key (create one in [Google AI Studio](https://aistudio.google.com/)).
- Android 8.0 (API 26) or later.
- Full photo access. On Android 14+ the partial "selected photos" option is not enough to watch a folder; the app detects it and asks to allow access to all photos instead.

## Building

This project uses Gradle with the Android Gradle Plugin, Kotlin, and Jetpack Compose. Building requires the Android SDK.

```
./gradlew assembleDebug
```

A release build additionally accepts signing credentials via Gradle properties (`releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias`, `releaseKeyPassword`); without them it falls back to debug signing.

```
./gradlew assembleRelease
```

CI (`.github/workflows/android-build.yml`) builds a release APK on every push to any branch (when build-related files change) and uploads it as a build artifact, using real release signing when the corresponding secrets are configured.
