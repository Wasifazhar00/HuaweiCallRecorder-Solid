# SolidCallRecorder v1.2

Hybrid Android call recorder focused on **both-sides voice recording** where the device allows it.

## What’s new in 1.2

- **VoIP-aware strategy ladder** – detects `MODE_IN_COMMUNICATION` and prioritizes `VOICE_COMMUNICATION` for WhatsApp / Wi‑Fi calling / third-party VoIP (Android 14+ best effort).
- **Live RMS silence check** – each candidate source is measured; pure silence is rejected and the next strategy is tried.
- **Source logging** – logcat + notification show which strategy actually succeeded (`DUAL_UPLINK_DOWNLINK`, `MIC_PLUS_DOWNLINK`, `VOICE_CALL`, `VOICE_COMMUNICATION`, `MIC_ONLY`).
- **Background hardening** – proper microphone FGS type, partial wake locks in both app process and shell UserService, Stop action on notification.
- **Cleaner lifecycle** – foreground promotion as early as possible to survive Android 12+ background-start rules.

## Capture ladder (Enhanced / Shizuku)

| Priority (cellular) | Priority (VoIP likely) | Strategy |
|---------------------|------------------------|----------|
| 1 | 2 | `DUAL_UPLINK_DOWNLINK` – best for SIM/VoLTE |
| 2 | 3 | `MIC_PLUS_DOWNLINK` – Samsung-friendly |
| 3 | 4 | `VOICE_CALL` |
| 4 | 1 | `VOICE_COMMUNICATION` – VoIP / Wi‑Fi calling |
| 5 | 5 | `MIC_ONLY` – last resort |

## Architecture

1. App binds **Shizuku UserService** (`ShellRecorderUserService`).
2. Service runs as **UID 2000 (shell)** with `WrappedShellContext` → identity `com.android.shell`.
3. `AudioRecord.Builder().setContext(shellContext)` opens privileged sources.
4. RMS gate rejects silent streams; first audible strategy wins.
5. Classic `MediaRecorder` path is automatic fallback if Shizuku is missing or all strategies fail.

## Setup

1. Install [Shizuku](https://github.com/RikkaApps/Shizuku) (or a maintained fork).
2. Start Shizuku via Wireless Debugging.
3. Open this app → **Request Shizuku Permission** → grant inside Shizuku.
4. Make a call. Notification shows `Enhanced · <STRATEGY>` when privileged path is active.

## Build (manual only)

Actions → **Build APK** → **Run workflow**. Never runs on push.

## Logs to watch

```
adb logcat -s ShellRecorderUS ShizukuEnhancedRecorder CallRecorderService
```

You will see lines like:

```
Trying strategy: DUAL_UPLINK_DOWNLINK
Strategy ACCEPTED: DUAL_UPLINK_DOWNLINK – VOICE_UPLINK + VOICE_DOWNLINK mixed
SUCCESS strategy=DUAL_UPLINK_DOWNLINK
```

## Legal

Comply with local one-party / two-party consent laws. You are solely responsible for lawful use.

## Credits

Technique family: scrcpy FakeContext, ShizuCallRecorder, cally (ladder + attribution).

**License**: Apache-2.0
