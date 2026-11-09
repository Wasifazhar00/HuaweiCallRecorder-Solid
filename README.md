# SolidCallRecorder

A solid, hybrid Android call recorder focused on **both-sides voice recording** where the device and Android version allow it.

> **Important**: True both-sides recording is heavily restricted by Google since Android 9/10.  
> This project combines two approaches:

1. **Classic path** – MediaRecorder + Telephony callbacks (works on some devices/OEM versions, often only one side).
2. **Enhanced path** – Shizuku / Wireless Debugging (ADB) method (current best FOSS way to get both sides without root on many phones).

The project is designed to work on **any Android phone** (not Huawei-specific).

## Features

- Manual start/stop recording
- Automatic recording via call state receiver
- Structure ready for contact-based rules
- Notification while recording
- Clean UI
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

This project implements the Classic path fully and provides clear hooks + documentation for the Enhanced (Shizuku) path.

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
      ui/                 # MainActivity
      service/            # Recording service + call state listener
      recorder/           # MediaRecorder wrapper + Enhanced interface
    res/
  build.gradle.kts
.github/workflows/build-apk.yml
```

## Recommended next steps for full both-sides

1. Integrate [Shizuku](https://github.com/RikkaApps/Shizuku) API
2. Use the shell-level audio capture technique (see ShizuCallRecorder / CallVault for reference architecture)
3. Fallback gracefully to classic MediaRecorder when Shizuku is not available

## Credits & Inspiration

- Modern FOSS projects: ShizuCallRecorder, JemRec, CallVault
- Classic Android Telephony + MediaRecorder patterns
- High-level workflow study of system recorders (no proprietary code used)

---

**License**: Apache-2.0
