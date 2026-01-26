# MovieMAX Mobile (Kivy)

This folder contains a Kivy-based, Android-ready version of MovieMAX.

## Run on Desktop (Windows)
1. Create a venv and install requirements:
   - `python -m venv venv`
   - `venv\Scripts\pip install kivy requests`
2. Run:
   - `venv\Scripts\python main.py`

## Build Android APK (Buildozer)
1. Install Buildozer (Linux/WSL recommended):
   - `pip install buildozer`
2. From this folder:
   - `buildozer android debug`

## Notes
- Requires at least one server connection to search/play.
- Database is downloaded automatically on first run.
- History resumes last playback position.
