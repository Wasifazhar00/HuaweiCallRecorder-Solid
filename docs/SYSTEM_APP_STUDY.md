# System dialer / phone APK study

Source packages examined (device extract):

| Package | Role |
|---------|------|
| `com.android.incallui` | Oplus/ColorOS in-call UI + **OplusRecorder** |
| `com.android.phone` | Telephony / phone process |
| `com.android.providers.telephony` | SMS/telephony provider |
| `com.google.android.dialer` | Google Dialer call recording |

## How they record (not copyable into a normal app)

### Oplus InCallUI
- Class: `com.oplus.media.OplusRecorder`
- Actions: `OPLUS_ACTION_START_RECORD`, auto-record, contact rules
- Default folder string: **`/Music/Recordings/Call Recordings`**
- Runs as **system / privileged** dialer component

### Google Dialer
- Permission: `android.permission.CAPTURE_AUDIO_OUTPUT` (signature/privileged)
- `AudioRecord.Builder`, `VoipDownlinkAudioRecordWrapper`
- `AudioInjector` / Dobby uplink injectors for features + disclosure audio
- Region-gated call recording; not available to third-party Play apps

## What we integrated into Solid Call Recorder

1. **Same public folder** as Oplus: `Music/Recordings/Call Recordings`
2. **Optional reflection** to `com.oplus.media.OplusRecorder` on ColorOS if the class is visible
3. Existing **Shizuku shell** path (closest third-party equivalent to privileged audio)
4. **Classic MediaRecorder** ladder as universal fallback

## What cannot be integrated

- Shipping Google/Oplus privileged services inside our APK
- Granting ourselves `CAPTURE_AUDIO_OUTPUT` without system signature
- Silent both-sides on stock Google Dialer without OEM or shell privilege
