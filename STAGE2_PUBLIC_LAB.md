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


## Stage 2 blockout materials

The pilot and moving obstacle use separate parameter instances of one small Filament lit/PBR material. The pilot uses dark graphite with restrained cyan emissive detail; the obstacle uses dark amber with a restrained warm emissive contribution. This is a visual blockout material pass, not final character or obstacle art.

The source material is `app/src/main/materials/blockout_pbr.mat`. GitHub Actions downloads the official Filament **v1.80.1** Linux tools, verifies the release archive SHA-256, and compiles a mobile-target `.filamat` using the same version as the Android runtime. The compiled package is generated into the APK assets during CI; the shader compiler itself is not included in the APK. Build test APKs from the Actions artifact, not from an unprepared local Gradle build.
