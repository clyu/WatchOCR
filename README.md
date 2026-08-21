# WatchOCR

An Android app that watches a folder for new screenshots/images, runs each one through Gemini for OCR, translates the extracted text into Traditional Chinese, and explains any idioms or slang it finds.

## Features

- **Folder monitoring**: pick an image folder (e.g. Screenshots) and the app watches it in the background, processing each new image as it arrives. An ongoing notification shows what it is doing, and tapping any of the app's notifications opens WatchOCR. Temporary failures are retried automatically; permanent ones (an image the API cannot read) are reported and skipped.
- **Starting and stopping**: monitoring needs both a folder and a Gemini API key, so clearing the key in Settings stops it — and so does a key Gemini rejects, since every image after it would be rejected the same way. Swiping the app away from the recents screen also stops it, and opening the app again resumes it. If monitoring stops for a reason you need to fix — the watched folder is gone, the key was cleared or rejected, or something else went wrong — a notification says so, and it clears itself once monitoring resumes.
- **Live only**: monitoring reacts to images as they arrive and never scans the folder for what it may have missed. Images that land while it isn't running — after a reboot, after the app was swiped away, or after the system killed it — are skipped, so import those manually if you need them.
- **Manual import**: pick a single image from the History tab at any time, independent of the watched folder.
- **OCR + translation + analysis**: each image is sent to the Gemini API, which returns the extracted text, a Traditional Chinese translation, and explanations for any idioms/slang — including a furigana (振り仮名) reading when an expression contains kanji.
- **History**: a scrollable list of past results with the source thumbnail, timestamp, extracted text (tap to copy), translation, and idiom/slang analysis. Newly arrived results automatically scroll into view.
- **Local persistence**: OCR results and images are stored on your device; nothing is uploaded except the image data sent to the Gemini API for processing. History can be auto-deleted after 1/7/30 days (default: kept forever) or cleared immediately from Settings.

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
