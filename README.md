# MovieMAX (Android)

MovieMAX is an Android app built with Kotlin + Jetpack Compose for searching and playing movies from configurable server sources.

## Features

- Jetpack Compose UI
- Playback via AndroidX Media3 (ExoPlayer)
- History + resume playback
- Optional AI recommendations (Groq) and poster fetching (OMDb)
- Server health checks (connects to the first available servers)

## Tech stack

- Kotlin, Coroutines
- Jetpack Compose + Material 3
- AndroidX Media3 (ExoPlayer)
- OkHttp + Kotlinx Serialization
- Coil (images)

## Getting started

### Prerequisites

- Android Studio (recommended)
- JDK 17
- Android SDK (compileSdk 34)

### Configure `local.properties`

This repo uses `local.properties` for local machine setup and optional API keys.

1. Copy `local.properties.example` → `local.properties`
2. Fill in at least:
   - `sdk.dir=...`
3. Optional integrations:
   - `GROQ_API_KEY=...`
   - `OMDB_API_KEY=...`

### Run

- Android Studio: open the folder and press Run
- CLI:
  - `./gradlew assembleDebug`
  - `./gradlew installDebug`

## CI

GitHub Actions runs `assembleDebug` and `lint` on pushes/PRs to `main`.

## Security note (important)

Keys added via `BuildConfig` end up inside the APK. Treat them as **non-secret**. For production use, move sensitive calls behind your own backend.

## Contributing

See `CONTRIBUTING.md`.

## License

MIT — see `LICENSE`.
