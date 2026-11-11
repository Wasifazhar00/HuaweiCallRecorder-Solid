# Solid Call Recorder 1.3

Silent hybrid call recorder. Enhanced path uses **Shizuku / Shizuku-Next** (shell). Offline mode uses Classic only (no Wi‑Fi debug).

## Modes

| Mode | Needs | Both sides |
|------|--------|------------|
| **Enhanced** | Shizuku-Next (or Shizuku) running + authorized | Best chance |
| **Offline only** | Nothing privileged | Usually your voice only |

Toggle **Offline only** in the app when you do not want Shizuku / developer options.

## Shizuku-Next (recommended)

- Same package + API as official Shizuku → this app works without code forks.
- Prefer **Shizuku-Next** for auto-start / recovery features.
- Start the manager once (Wireless Debugging, USB ADB, or **root**). Some Next builds can keep the service with less reliance on constant Wi‑Fi.
- Grant this app in the manager UI.

We do **not** embed Shizuku-Next in the APK (size, signing, updates). Install it separately from its GitHub releases.

## v1.3 changes

- Offline-only preference (Classic path, no Shizuku)
- Shizuku-Next detection / wording
- Faster UserService bind timeout, leaner release APK (R8 + resource shrink)
- Silent MIN notification, lower battery use
- No recording beep/announcement from this app

## Build

GitHub Actions → **Build APK** → Run workflow (manual only). Use **release** for the smallest APK.

## Legal

Follow local consent laws. You are responsible for lawful use.

Apache-2.0
