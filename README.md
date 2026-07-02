# WatchOCR

An Android app that watches a folder for new screenshots/images, runs each one through Gemini for OCR, translates the extracted text into Traditional Chinese, and explains any idioms or slang it finds.

## Features

- **Folder monitoring**: pick an image folder (a MediaStore bucket, e.g. Screenshots) and a foreground service reacts to new images the moment MediaStore indexes them, via a `ContentObserver` (with a periodic fallback sweep). Only images added after the folder was selected are OCR'd; failed images are retried a few times.
- **Manual import**: pick a single image from the History tab at any time, independent of the watched folder.
- **OCR + translation + analysis**: each image is sent to the Gemini API, which returns the extracted text, a Traditional Chinese translation, and explanations for any idioms/slang, via a structured JSON response schema.
- **History**: a scrollable list of past results with the source thumbnail, timestamp, extracted text (tap to copy), translation, and idiom/slang analysis. Newly arrived results automatically scroll into view.
- **Local persistence**: OCR results and images are stored on-device (Room database + app-private file storage); nothing is uploaded except the image data sent to the Gemini API for processing. History can be auto-deleted after 1/7/30 days (default: kept forever) or cleared immediately from Settings.

## How it works

1. In **Settings**, choose an image folder to watch (requires the photo access permission), enter your Gemini API key, and optionally change the model (defaults to `gemini-3.1-flash-lite`).
2. Once both a folder and API key are set, `DirectoryMonitorService` starts as a foreground service, registers a `ContentObserver` on the MediaStore images collection, and scans the selected bucket whenever it changes (or at a fallback interval). On Android 10+ MediaStore hides rows that are still being written (`IS_PENDING`), so only fully written images are picked up.
3. Each new image's bytes are base64-encoded and sent to `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` with a JSON response schema requesting `ocr`, `translation`, and `analysis` fields.
4. The image is copied into app-private storage and the result is saved as an `OcrRecord`, which appears at the top of the **History** tab.

## Project structure

```
app/src/main/java/com/watchocr/app/
├── MainActivity.kt              # Compose UI shell, navigation, image picker
├── ManualOcrViewModel.kt        # Runs manual imports so they survive rotation
├── NotificationChannels.kt
├── data/
│   ├── AppDatabase.kt            # Room database
│   ├── OcrRecord.kt              # OCR result entity
│   ├── OcrRecordDao.kt
│   ├── MonitoredFile.kt          # Tracks seen files + OCR retry state
│   ├── MonitoredFileDao.kt
│   ├── MediaStoreImages.kt       # MediaStore bucket/image queries
│   └── SettingsDataStore.kt      # DataStore-backed app settings
├── network/
│   └── GeminiClient.kt           # Gemini API request/response handling
├── ocr/
│   └── OcrProcessor.kt           # Orchestrates OCR -> storage -> persistence
├── service/
│   └── DirectoryMonitorService.kt # Foreground service watching the folder
└── ui/
    ├── HistoryScreen.kt
    ├── SettingsScreen.kt
    └── theme/
```

## Requirements

- A Gemini API key (create one in [Google AI Studio](https://aistudio.google.com/)).
- Android 8.0 (API 26) or later.

## Building

This project uses Gradle with the Android Gradle Plugin, Kotlin, and Jetpack Compose. Building requires the Android SDK.

```
./gradlew assembleDebug
```

A release build additionally accepts signing credentials via Gradle properties (`releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias`, `releaseKeyPassword`); without them it falls back to debug signing.

```
./gradlew assembleRelease
```

CI (`.github/workflows/android-build.yml`) builds a release APK on every push/PR to `main` and uploads it as a build artifact, using real release signing when the corresponding secrets are configured.

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Room (persistence), DataStore (settings)
- OkHttp (networking), Coil (image loading)
- MediaStore + `ContentObserver` for event-driven folder monitoring (needs only the photo read permission)
