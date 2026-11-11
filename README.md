# Solid Call Recorder 2.0

Production-oriented Android call recorder focused on **automatic incoming & outgoing** recording, silent operation, and best-effort both-sides capture.

## What works out of the box (one APK)

- **Auto-record** in and out calls (TelephonyCallback monitor + boot restart)
- Silent: **no beep / no announcement** from this app
- Classic audio path without any extra app
- Optional **Enhanced** path when Shizuku / Shizuku-Next is installed and authorized

## What Android does not allow

**Shizuku-Next cannot be embedded inside this APK** so that both-sides cellular audio works with zero other apps and zero developer setup. Shell identity (`CAPTURE_AUDIO_OUTPUT` / `VOICE_CALL`) is reserved for system or ADB-started processes. Huawei’s stock recorder is a **system/dialer** component — a normal Play-style APK cannot become that.

| Goal | Reality |
|------|---------|
| Auto start/stop on calls | Yes (this app) |
| Silent (no tone from us) | Yes |
| Both sides without any helper | Not possible on modern stock Android |
| Both sides with Shizuku-Next | Best effort, device-dependent |

## Setup

1. Install APK, open app, grant mic + phone + notifications.
2. Leave **Auto-record** ON.
3. Allow unrestricted battery for the app.
4. (Optional) Install Shizuku-Next, start it, authorize this app for better both-sides odds.

Recordings: `Android/data/com.solid.callrecorder/files/CallRecordings/`

## Build

Actions → **Build APK** → Run workflow. Prefer **release** for minified APK.

## Legal

Comply with local consent laws.

Apache-2.0
