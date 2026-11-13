package com.solid.callrecorder.recorder

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.solid.callrecorder.ui.MainActivity
import java.io.File

/**
 * Single intelligent entry for all capture backends.
 * Tries highest-quality path first, logs every decision, never plays a beep.
 */
class SmartCaptureEngine(private val context: Context) {

    private val oplus = OplusRecorderBridge(context)
    private val shizuku = ShizukuEnhancedRecorder(context)
    private val dual = GoogleStyleDualCapture(context)
    private val classic = CallRecorder(context)

    private var active: Backend = Backend.NONE
    private var lastNumber: String? = null

    enum class Backend { NONE, OPLUS, SHIZUKU, DUAL, CLASSIC }

    data class Result(
        val ok: Boolean,
        val backend: Backend,
        val detail: String
    )

    fun start(phoneNumber: String?, forceOffline: Boolean = false): Result {
        if (active != Backend.NONE) {
            return Result(false, active, "already recording")
        }
        lastNumber = phoneNumber

        val offline = forceOffline || context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_OFFLINE_ONLY, false)

        val mode = try {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        } catch (_: Exception) {
            AudioManager.MODE_NORMAL
        }
        val voip = mode == AudioManager.MODE_IN_COMMUNICATION
        Log.i(TAG, "start number=$phoneNumber offline=$offline voip=$voip audioMode=$mode")

        // 1) OEM Oplus (best on ColorOS when class is callable)
        if (oplus.isAvailable()) {
            if (oplus.start(phoneNumber)) {
                active = Backend.OPLUS
                return Result(true, Backend.OPLUS, "OplusRecorder VOICE_CALL")
            }
            Log.w(TAG, "Oplus present but start failed")
        }

        // 2) Shizuku privileged dual / VOICE_CALL
        if (!offline && shizuku.isAvailable()) {
            if (shizuku.start(phoneNumber)) {
                active = Backend.SHIZUKU
                val strat = shizuku.lastStrategyLabel() ?: "shell"
                return Result(true, Backend.SHIZUKU, "Shizuku $strat")
            }
            Log.w(TAG, "Shizuku available but start failed")
        }

        // 3) In-process dual uplink/downlink or VOICE_CALL AudioRecord
        if (dual.start(phoneNumber)) {
            active = Backend.DUAL
            return Result(true, Backend.DUAL, "AudioRecord dual/VOICE_CALL")
        }

        // 4) MediaRecorder classic ladder
        if (classic.start(phoneNumber)) {
            active = Backend.CLASSIC
            return Result(true, Backend.CLASSIC, "MediaRecorder source=${classic.activeSource}")
        }

        active = Backend.NONE
        return Result(false, Backend.NONE, "all backends failed")
    }

    fun stop(): Pair<File?, Backend> {
        val used = active
        val file = try {
            when (active) {
                Backend.OPLUS -> oplus.stop()
                Backend.SHIZUKU -> shizuku.stop()
                Backend.DUAL -> dual.stop()
                Backend.CLASSIC -> classic.stop()
                Backend.NONE -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop", e)
            null
        }
        active = Backend.NONE
        Log.i(TAG, "stop backend=$used file=${file?.name} bytes=${file?.length()}")
        return file to used
    }

    fun isRecording(): Boolean = active != Backend.NONE

    fun release() {
        try {
            if (active != Backend.NONE) stop()
        } catch (_: Exception) {
        }
        try {
            shizuku.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "SmartCapture"
    }
}
