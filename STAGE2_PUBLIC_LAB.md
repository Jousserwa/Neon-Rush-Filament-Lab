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


## Automated performance sweep

On the test phone, launch the public lab and **long-press anywhere in the playfield for about 0.8 seconds**. The app runs eight profiles in sequence. Each profile gets a 2-second warm-up followed by a 5-second measurement window (about 56 seconds total):

1. FULL BASELINE — bloom on, MSAA 2x, FXAA on, native resolution, pilot and obstacle visible.
2. MSAA OFF / FXAA ONLY — MSAA off, FXAA on.
3. MSAA ONLY / FXAA OFF — MSAA 2x, FXAA off.
4. NO AA — MSAA and FXAA off.
5. BLOOM OFF — both AA methods off, bloom disabled.
6. BLOOM LOW RES — bloom at 96-pixel resolution and 2 levels, low quality.
7. DYNAMIC 75-85% — both AA methods off, bloom at normal low-quality settings, dynamic resolution constrained to 75-85%.
8. CORRIDOR ONLY — pilot and obstacle renderables hidden.

The app restores FULL BASELINE after the sweep. It writes `NeonRush_Stage2_ProfileCycle_*.csv` to the device Downloads folder on Android 10+, or app-specific Documents storage on older Android. If CSV storage fails, each row is also emitted as an `NRStage2CSV` log line.

The CSV records callback rate, accepted render rate, CPU update/render-call averages, false returns, callback gaps, Filament GPU frame-duration average/maximum (when the backend exposes valid timing), thermal state, power-save state and display refresh rate. The GPU fields are backend-reported frame-history timing, not a universal GPU utilization percentage; unsupported timing is recorded as zero with zero samples.

For comparable results: let the phone cool, use the same display refresh setting, close background apps, avoid touching the screen during the sweep, and run the sweep twice. The first run can be affected by shader compilation/cache warm-up and device scheduling.

## GPU diagnosis

The app samples `ModelViewer.getRenderer().getFrameInfoHistory()` and logs `gpuFrameAvgMs`, `gpuFrameMaxMs` and `gpuSamples` alongside the existing CPU and callback data. Compare valid GPU frame duration with the 16.67 ms 60 Hz frame budget. Sustained GPU durations near or above that budget, coupled with stable callback cadence and reduced accepted renders, support a GPU-bound diagnosis. Short GPU durations with callback gaps point toward scheduling, pacing, CPU/UI work or presentation behavior instead.

For deeper device traces, enable Android Developer options and the **System Tracing** tile (if available), record Graphics/SurfaceFlinger and GPU frequency/counter categories only if the device exposes them, then open the trace in [Perfetto UI](https://ui.perfetto.dev). Many mobile GPU drivers do not expose hardware counters to Perfetto; absence of counters is not evidence that the GPU is idle.

## On-screen controls

- Tap the compact top-left profiler panel to expand/collapse diagnostics.
- Long-press the playfield for about 0.8 seconds to run the timed CSV sweep.
- Drag outside the profiler panel to steer the synthetic pilot.
