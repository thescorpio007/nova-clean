# Nova Clean

Closes stuck background apps and clears app caches on Android 7.0+ (phones and TV boxes), without root.
Signed **A.HAMROUNI**.

## How it works: engines (strongest ready one is used automatically)

| Engine | Setup | Can do |
|---|---|---|
| Root (`su`) | none, only if the device already has root | force-stop, clear all caches, running-process list |
| Shizuku / ADB | one-time, see below | force-stop, clear all caches (`pm trim-caches`), running-process list, foreground-service detection |
| Basic | none | `killBackgroundProcesses` (background/cached processes only) |

Optional: **Usage Access** gives a "recently used" list when no shell engine is available.

## Safety

1. **Hard protection list (code, always wins):** system apps, launcher, keyboards, accessibility services,
   device admins, notification listeners, VPN apps, apps running a foreground service (playing/recording),
   apps used in the last 3 minutes, Nova Clean itself, plus anything you tap **Protect** on.
2. **AI (Gemini) only advises** on the remaining apps: SAFE / CAUTION / NEVER. NEVER locks the app.
   Only SAFE apps are pre-selected. You confirm before anything is closed.
3. Only package names, app names and last-used times are sent to the AI.

## Setting up Shizuku (one time)

- **Android 11+ (phone):** install Shizuku, enable Developer options > Wireless debugging, in Shizuku choose
  "Pairing" then "Start". Open Nova Clean > **Grant Shizuku**.
- **Android 7-10 (and TV boxes):** enable Developer options > USB debugging (and "ADB over network" on boxes).
  Run once from a PC: `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`
  (Shizuku shows the exact command). On a box, Shizuku can also start itself over network ADB.
  It must be re-started after each reboot.

## Build (GitHub Actions)

1. Create repo `nova-clean` (public), upload this folder, open the **Actions** tab (use Chrome).
2. Run **Build Nova Clean APK**. The APK `NovaClean-1.0-build-NN.apk` appears under the run and in Releases.
3. Optional secrets for the same signing key as your other Nova apps:
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Next steps

Scheduled auto-clean, in-app update from GitHub Releases, TV-first layout polish.
