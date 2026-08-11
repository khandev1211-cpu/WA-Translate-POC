# WA Translate — Proof of Concept (Step 1)

## Ye kya hai?

Ye poori translation app **nahi** hai. Ye ek chhota test hai jo pehle check karega:

> "Kya aapke Techno Spark 5 Pro pe, WhatsApp call ke dauran, dusre insaan ki
> awaaz ko system-level se capture karna technically possible hai ya nahi?"

Iska jawab poore project ka direction decide karega.

## Ye zaroori kyun hai?

Android normally **VoIP call audio (WhatsApp, etc.) ko system-level capture se
block karta hai** — ye ek security feature hai taake koi app secretly calls na
sune. Humne `AudioPlaybackCaptureConfiguration` use ki hai jo sirf media/
game/unknown audio capture kar sakti hai — call audio ke liye Android
officially koi permission nahi deta.

Mumkin hai ye test **silence** capture kare. Agar aisa hua, to matlab:
- Ye raasta band hai (Android ki security policy ki wajah se)
- Humein Option 1/2 (loudspeaker + mic-based) approach pe wapas jaana hoga

Agar signal capture ho jaye, to hum aage STT + Translation + overlay wire karenge.

---

## Project Analysis

### Overview

| Item | Detail |
|------|--------|
| **Project Name** | WATranslate |
| **Type** | Android Application (Proof of Concept) |
| **Package** | `com.watranslate.app` |
| **Language** | Kotlin |
| **Min SDK** | 29 (Android 10) — required for `AudioPlaybackCaptureConfiguration` |
| **Target SDK** | 34 (Android 14) |
| **Compile SDK** | 34 |
| **Version** | `0.1-poc` (versionCode 1) |
| **Build System** | Gradle 8.7 (Kotlin DSL) |
| **AGP** | 8.5.2 |
| **Kotlin** | 1.9.24 |
| **JVM Target** | 17 |

### Tech Stack & Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `androidx.core:core-ktx` | 1.13.1 | AndroidX core extensions |
| `androidx.appcompat:appcompat` | 1.7.0 | AppCompat support |
| `com.google.android.material:material` | 1.12.0 | Material Design components |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | Constraint layout (declared, not used in current layout) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.1 | Async audio processing |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP client for future Google Cloud STT/Translate REST calls |

> **Note:** Google Cloud Speech-to-Text & Translation SDKs are heavy gRPC libraries
> not meant for direct on-device use with embedded credentials. The project plans
> to call them via REST + API key instead (see comment in `app/build.gradle.kts`).

### Project Structure

```
WATranslate-POC/
├── build.gradle.kts                  # Top-level build config (AGP + Kotlin plugins)
├── settings.gradle.kts               # Repositories + module includes
├── gradle.properties                 # JVM args, AndroidX flag, Kotlin style
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties # Gradle 8.7 distribution
├── app/
│   ├── build.gradle.kts              # App module config (SDK, deps, ViewBinding)
│   └── src/main/
│       ├── AndroidManifest.xml       # Permissions + Activity/Service declarations
│       ├── java/com/watranslate/app/
│       │   ├── MainActivity.kt       # UI + MediaProjection permission flow
│       │   └── CaptureService.kt     # Foreground service: audio capture + analysis
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml # Single-screen test UI
│           ├── values/
│           │   ├── strings.xml       # App name
│           │   └── themes.xml        # WhatsApp-inspired color theme
│           └── (drawable/, xml/ — empty placeholder dirs)
```

### Architecture & Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                         MainActivity                                 │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │ 1. User taps "Start Test Capture (10 sec)"                      │ │
│  │ 2. Launches MediaProjectionManager.createScreenCaptureIntent()  │ │
│  └──────────────────────────────┬──────────────────────────────────┘ │
│                                 │ permission granted                 │
│                                 ▼                                    │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │ 3. Starts CaptureService (foreground, mediaProjection type)     │ │
│  └──────────────────────────────┬──────────────────────────────────┘ │
└─────────────────────────────────┼────────────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        CaptureService                                │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │ • Builds AudioPlaybackCaptureConfiguration with:                │ │
│  │     - USAGE_MEDIA                                               │ │
│  │     - USAGE_GAME                                                │ │
│  │     - USAGE_UNKNOWN                                             │ │
│  │   (USAGE_VOICE_COMMUNICATION is NOT capturable by design)       │ │
│  ├─────────────────────────────────────────────────────────────────┤ │
│  │ • Creates AudioRecord (16kHz mono, 16-bit PCM)                  │ │
│  ├─────────────────────────────────────────────────────────────────┤ │
│  │ • Records 10 seconds to capture_test.pcm                        │ │
│  ├─────────────────────────────────────────────────────────────────┤ │
│  │ • Computes max amplitude to detect signal vs silence            │ │
│  ├─────────────────────────────────────────────────────────────────┤ │
│  │ • Broadcasts result back to MainActivity via STATUS_UPDATE      │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### Permissions (AndroidManifest.xml)

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Future Google Cloud STT / Translate REST API calls |
| `ACCESS_NETWORK_STATE` | Network state checking |
| `SYSTEM_ALERT_WINDOW` | Future floating translation overlay over WhatsApp |
| `FOREGROUND_SERVICE` | Run persistent foreground service during capture |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Media projection foreground service type (API 34+) |
| `RECORD_AUDIO` | Requested even for playback capture — some OEMs require it |

### Key Technical Details

1. **AudioPlaybackCaptureConfiguration** (API 29+):
   - Can only capture `USAGE_MEDIA`, `USAGE_GAME`, or `USAGE_UNKNOWN` audio.
   - Android explicitly **excludes** `USAGE_VOICE_COMMUNICATION` (VoIP call audio)
     from capture for privacy/security reasons.
   - WhatsApp calls use `USAGE_VOICE_COMMUNICATION`, so this path will likely
     record **silence** on stock Android.
   - Some OEM skins behave differently — hence the real-device test.

2. **CaptureService**:
   - Foreground service with `mediaProjection` type.
   - Records raw PCM (16kHz mono, 16-bit) to `capture_test.pcm`.
   - Computes max amplitude: `< 50` → "Mostly SILENCE", otherwise "Signal detected".
   - Uses Kotlin Coroutines (`Dispatchers.IO`) for non-blocking recording.

3. **MainActivity**:
   - Uses `registerForActivityResult` for the MediaProjection permission flow.
   - Receives status updates via a `BroadcastReceiver` (`com.watranslate.app.STATUS_UPDATE`).
   - Uses ViewBinding (`ActivityMainBinding`).

4. **UI**:
   - Single screen with title, explanation, capture button, status, and result.
   - WhatsApp-inspired theme colors (`#1EBEA5`, `#128C7E`, `#25D366`).

### Build & Run

**Prerequisites:**
- Android Studio (latest stable)
- JDK 17
- Android device with USB debugging enabled (Android 10+)

**Steps:**
1. Open the project in Android Studio (File → Open → select this folder)
2. Let Gradle sync complete (first time takes a while)
3. Connect your phone via USB with USB debugging enabled
4. Run the app (Run button, or `./gradlew installDebug`)
5. **Start a WhatsApp call** with someone (or call yourself from another device) and have them speak
6. Open the app and tap **"Start Test Capture (10 sec)"** — keep the call active
7. Allow the system permission dialog ("Start recording or casting?")
8. Wait 10 seconds — the result will appear on screen:
   - **"Mostly SILENCE captured"** → Android blocked call audio (expected outcome)
   - **"Signal detected"** → something was captured; file saved to `capture_test.pcm` for manual verification

**Build commands:**
```bash
./gradlew build          # Full build
./gradlew installDebug   # Build + install to connected device
./gradlew assembleDebug  # Build APK only
```

### Output File

The captured audio is saved to the phone's internal storage:
```
Android/data/com.watranslate.app/files/capture_test.pcm
```

Raw PCM, 16kHz mono, 16-bit — playable in VLC or Audacity via "raw audio import".

### Test Results

| Date | Device | Result | Notes |
|------|--------|--------|-------|
| — | Techno Spark 5 Pro | Pending | Awaiting real-device test |

### Next Steps

1. **Run the test** on the Techno Spark 5 Pro and record the result.
2. **If silence is captured** (expected): switch to the loudspeaker + microphone
   approach — reliable but less seamless.
3. **If signal is captured**: wire up:
   - Speech-to-Text (Google Cloud STT via REST + API key)
   - Translation API (Google Cloud Translate via REST)
   - Floating overlay to display live translations over WhatsApp