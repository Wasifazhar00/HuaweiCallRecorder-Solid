# SolidCallRecorder

Hybrid Android call recorder focused on **both-sides voice recording** where the device and Android version allow it.

> **Important**: True both-sides recording is heavily restricted by Google since Android 9/10.  
> This project combines two approaches and now includes a real privileged capture path.

## Architecture

| Path | How it works | Both sides? |
|------|--------------|-------------|
| **Enhanced (Shizuku)** | UserService runs as shell UID 2000 + `WrappedShellContext` (`com.android.shell`) → opens `VOICE_UPLINK` + `VOICE_DOWNLINK` (or fallbacks) | Best chance on many devices |
| **Classic** | Normal `MediaRecorder` + Telephony callbacks | Often one side only on modern Android |

The service **prefers Enhanced** when Shizuku is ready, otherwise falls back to Classic automatically.

### Enhanced path details (the hard part)

1. App binds a **Shizuku UserService** (`ShellRecorderUserService`).
2. That process runs as **UID 2000 (shell)**.
3. A `WrappedShellContext` makes the process look like the real system package `com.android.shell`.
4. `AudioRecord.Builder().setContext(shellContext)` is used so AudioFlinger accepts the privileged identity.
5. Capture ladder:
   - Dual `VOICE_UPLINK` + `VOICE_DOWNLINK` (mixed to mono WAV)
   - `VOICE_CALL`
   - `VOICE_COMMUNICATION`
   - `MIC` (last resort)
6. Output is a 16-bit PCM WAV under `Android/data/com.solid.callrecorder/files/CallRecordings/`.

This technique is the same family used by scrcpy (FakeContext), ShizuCallRecorder, and cally.

## Features

- Manual start/stop + automatic recording via call-state receiver
- Real Shizuku UserService + dual-source attempt
- Automatic fallback Enhanced → Classic
- Status UI for permissions + Shizuku readiness
- Foreground notification while recording
- **Manual GitHub Actions build only** (`workflow_dispatch` – never automatic)

## Legal Notice

Call recording laws differ by country and state (one-party vs two-party consent).  
**You are solely responsible** for complying with local laws.  
This app is provided for legitimate, lawful use only.

## Requirements for Enhanced (both-sides) path

1. Install **Shizuku** ([RikkaApps](https://github.com/RikkaApps/Shizuku) or a maintained fork such as thedjchi).
2. Start Shizuku (Wireless Debugging recommended – no root).
3. Open this app → tap **Request Shizuku Permission**.
4. Grant the permission inside the Shizuku app.
5. Status should show “Shizuku: Ready (Enhanced path available)”.

Android 12+ (API 31+) is strongly recommended for the `AudioRecord.Builder.setContext` path.

## Building the APK (Manual only)

1. Go to the **Actions** tab of this repository
2. Select **Build APK**
3. Click **Run workflow** → choose debug or release → **Run workflow**
4. Wait for the job to finish
5. Download the APK from the Artifacts section

The workflow uses `workflow_dispatch` only — it will **never** run on push or pull request.

## Project Structure

```
app/
  src/main/
    aidl/.../IShellRecorder.aidl          # Binder contract for UserService
    java/.../callrecorder/
      ui/MainActivity.kt
      service/CallRecorderService.kt      # Prefers Enhanced → Classic
      recorder/
        CallRecorder.kt                   # Classic MediaRecorder path
        ShizukuEnhancedRecorder.kt        # Binds UserService & drives it
        AudioSourceSelector.kt
      shizuku/
        WrappedShellContext.kt            # com.android.shell identity
        ShellRecorderUserService.kt       # Privileged dual AudioRecord logic
.github/workflows/build-apk.yml
```

## Limitations & honesty

- Success of both-sides capture still depends on OEM / Android version / call type (cellular vs VoIP).
- Some manufacturers further restrict even shell-identity access.
- Dual tracks are currently mixed to a single mono WAV for simplicity; separate left/right channels can be added later.
- VoIP / third-party apps are best-effort (better on Android 14+ with additional work).

## Credits & Inspiration

- scrcpy (FakeContext technique)
- ShizuCallRecorder (native privileged capture)
- cally (dual-track ladder + attribution)
- Shizuku by RikkaApps

---

**License**: Apache-2.0
