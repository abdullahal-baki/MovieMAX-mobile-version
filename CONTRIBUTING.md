# Contributing

Thanks for taking the time to contribute!

## Quick start

1. Fork the repo and create a feature branch.
2. Make your change (keep it focused).
3. Run checks locally:
   - `./gradlew assembleDebug`
   - `./gradlew lint`
4. Open a Pull Request with:
   - what changed
   - why it changed
   - screenshots (if UI-related)

## Development setup

- Android Studio (recommended) + Android SDK
- JDK 17

Create `local.properties` (never commit it). You can start from `local.properties.example`.

## Code style

- Prefer small, readable functions and meaningful names.
- Keep UI logic in Compose + state in ViewModel (avoid networking in composables).
- If you add new dependencies, explain why in the PR.

## Commit messages

Use clear, conventional messages when possible:

- `feat: ...`
- `fix: ...`
- `docs: ...`
- `refactor: ...`

## Reporting issues

Please include:
- device + Android version
- steps to reproduce
- expected vs actual behavior
- logs (if possible)

