# Neon Rush — Public Filament Integration Lab

This repository is a public, non-proprietary technical test harness. It is **not** the Neon Rush production game and does not contain the production game's source code, proprietary assets, credentials, backend configuration, store configuration, advertising IDs, signing keys, or user data.

## Purpose

Validate the Filament 1.80.1 renderer inside a native Android gameplay-style screen, using synthetic pilot and obstacle overlay graphics. The corridor GLB is the same isolated technical test asset already used in the Filament renderer lab.

## What this lab can test

- Filament corridor rendering and lifecycle on real Android devices.
- Android SurfaceView attachment, detachment, pause/resume and renderer teardown.
- A synthetic gameplay overlay with touch-driven pilot positioning.
- HUD layering over a live Filament scene.
- Android build compatibility and renderer performance.

## What it does not prove

This harness does not contain the actual Neon Rush simulation, production Compose UI, real game controls, economy, social systems, bosses, production art, or monetization. It cannot establish complete production integration compatibility. Those require a later controlled integration into a protected copy of the actual game.

## Build

Run:

`gradle :app:assembleDebug --no-daemon`

The GitHub Actions workflow builds the debug APK but does not upload an Actions artifact. This avoids consuming the repository's limited Actions artifact storage. The APK is generated at `app/build/outputs/apk/debug/app-debug.apk` on the runner and is not retained after the job.

## Safety rules

- Do not add production source files or proprietary game assets.
- Do not add Firebase, RevenueCat, AdMob production configuration, API keys, passwords, signing keys, or private endpoints.
- Use synthetic test data only.
- Keep the actual production repository and the private full-game integration repository unchanged.
