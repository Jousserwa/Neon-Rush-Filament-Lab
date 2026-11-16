# Neon Rush — Filament Lab

A public, isolated Android laboratory for testing the Filament 3D renderer. This repository contains the standalone renderer test app only.

## Included

- Filament, glTF I/O and Filament utilities **1.80.1**.
- The Stage 1 corridor renderer test Activity, including the tested SurfaceView / ModelViewer lifecycle handling and resume-camera correction.
- The small corridor GLB test asset (stored as Base64 text and loaded by the app).
- A separate Android application ID: `com.neonrush.filamentspike`.

## Build

The GitHub Actions workflow builds the debug APK using JDK 21, Android SDK 37 and Gradle 9.3.1.

To build locally with a compatible Android SDK and Gradle installation:

```bash
gradle :app:assembleDebug
```

APK output:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Scope and safety

This is **not** the Neon Rush production game and is not the Stage 2 integration copy. It contains no production gameplay code, monetization integrations, Firebase configuration, production signing keys, or production credentials. The public lab is intentionally limited to the standalone Filament renderer test.

The tested renderer is a technical baseline, not final game art or a production-ready game environment.


## Automated validation

Every change to the app source, Gradle configuration, or this workflow triggers the GitHub Actions Android build. The workflow compiles the debug APK without uploading build artifacts, keeping the public lab independent of any signing secrets and avoiding unnecessary artifact storage.
