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

The GitHub Actions workflow builds the debug APK, uploads it as the `stage2-public-lab-apk` artifact for 7 days, and also publishes it as a prerelease asset. Download the Actions artifact from the successful workflow run's **Artifacts** section; this is the preferred download route for mobile devices.

## Safety rules

- Do not add production source files or proprietary game assets.
- Do not add Firebase, RevenueCat, AdMob production configuration, API keys, passwords, signing keys, or private endpoints.
- Use synthetic test data only.
- Keep the actual production repository and the private full-game integration repository unchanged.
