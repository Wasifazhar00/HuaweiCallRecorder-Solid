# SolidCallRecorder

A solid, hybrid Android call recorder focused on **both-sides voice recording** where the device and Android version allow it.

> **Important**: True both-sides recording is heavily restricted by Google since Android 9/10.  
> This project combines two approaches:

1. **Classic path** – MediaRecorder + Telephony callbacks (works on some devices/OEM versions, often only one side).
2. **Enhanced path** – Shizuku skeleton (ready for both-sides implementation).

The project is designed to work on **any Android phone** (not Huawei-specific).

## Features

- Manual start/stop recording
- Automatic recording via call state receiver
- Shizuku integration skeleton (`ShizukuEnhancedRecorder`)
- Automatic fallback: Enhanced → Classic
- Status display for Shizuku readiness
- Notification while recording
- **Manual GitHub Actions build only** (never runs automatically)

## Legal Notice

Call recording laws differ by country and state (one-party vs two-party consent).  
**You are solely responsible** for complying with local laws.  
This app is provided for legitimate, lawful use only.

## How both-sides recording works today (2026)

| Method                    | Both sides? | Root needed | Extra setup          | Reliability          |
|---------------------------|-------------|-------------|----------------------|----------------------|
| Classic MediaRecorder     | Rarely      | No          | None                 | Low on modern Android|
| Shizuku + hidden APIs     | Yes (many)  | No          | Install Shizuku      | Good                 |
| Built-in Wireless Debugging | Yes (many) | No        | One-time ADB pairing | Good                 |
| Root                      | Yes         | Yes         | Root                 | Highest              |

## Shizuku Skeleton (Enhanced path)

The class `ShizukuEnhancedRecorder` is already integrated:

- Detects if Shizuku is installed
- Checks if this app is authorized
- Requests permission via Shizuku API
- Service prefers Enhanced path when available, falls back to Classic

**What still needs implementation** (the hard part):

The actual both-sides audio capture using Shizuku privileges.  
Typical techniques (see ShizuCallRecorder / CallVault):

- Privileged `AudioRecord` / hidden AudioSystem APIs via Shizuku UserService
- Or shell-level recording commands executed through Shizuku

The skeleton returns `false` from `start()` so the app safely falls back to the classic path until you complete this part.

### Setup for users

1. Install [Shizuku](https://github.com/RikkaApps/Shizuku)
2. Start Shizuku (Wireless Debugging is recommended – no root needed)
3. Open this app → tap **Request Shizuku Permission**
4. Grant the permission inside the Shizuku app
5. Status should show "Shizuku: Ready"

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
    java/.../callrecorder/
      ui/                 # MainActivity (shows Shizuku status)
      service/            # Prefers Enhanced → Classic
      recorder/
        CallRecorder.kt              # Classic path
        AudioSourceSelector.kt
        ShizukuEnhancedRecorder.kt   # Enhanced skeleton
        EnhancedRecorder (interface)
    res/
  build.gradle.kts                   # includes Shizuku dependencies
.github/workflows/build-apk.yml
```

## Credits & Inspiration

- Modern FOSS projects: ShizuCallRecorder, JemRec, CallVault
- Classic Android Telephony + MediaRecorder patterns
- Shizuku by RikkaApps

---

**License**: Apache-2.0
