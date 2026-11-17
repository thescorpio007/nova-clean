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

## 1.1

- **Settings** holds all configuration (AI, one-tap options, protected apps, permissions, engines, backup).
- **Files:** everything Nova Clean saves is in `Download/NovaClean/` (`Backups/` and `history.txt`).
- **Backup / Restore:** local, optional password (encrypted, includes the API key). Without a password the key is left out.
- **One-tap:** the *Clean now* button, the home-screen widget and the long-press icon shortcut all run the same job,
  customised in Settings (what to close, caches, result message/notification, widget text).
- **Permissions page:** battery (unrestricted), display over other apps, usage access, notifications, storage (Android 7-9),
  Shizuku, autostart page of the phone maker, app info page.

## 1.2 — signing key (do once)

Updates only install over each other when every build is signed with the same key.
In the repo: Settings > Secrets and variables > Actions > New repository secret. Add four secrets:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (= `novaclean`), `KEY_PASSWORD`. Keep a private backup of the key.
The workflow refuses to publish an APK when `KEYSTORE_BASE64` is missing.

## 1.3
- Settings → 1: **Check API & models** lists the models your key can use, tests each (score 1-10, speed), and selects the fastest good one (plus a backup model).
- **💬 Chat** button beside Settings: talk freely with the AI; once you agree, it changes the app settings (with Undo).
- Updates moved to the bottom of Settings (section 9).

## 1.4
- Always-visible scroll bars (home, chat, settings), Paste button for the API key, remote-friendly text boxes (OK/Enter to edit), key is cleaned of spaces and the real Google error is shown.

## 2.0
- Select all / Unselect all (unprotected apps only), caches of all apps cleared after Close selected (needs Shizuku/root), Explore system apps option (AI assesses, you approve; core components locked), Backup/Restore no longer lockable, Cancel button and time limits, results under the buttons. Build number increases with every build.

- 2.0: VPN apps are no longer auto-locked (you decide), per-app Unprotect with warning (also in Settings → 3), Clean now includes system apps that pass the locks and are AI-SAFE.
