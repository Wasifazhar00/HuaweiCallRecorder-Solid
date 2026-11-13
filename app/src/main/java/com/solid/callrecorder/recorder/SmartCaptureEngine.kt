package com.solid.callrecorder.recorder

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.solid.callrecorder.ui.MainActivity
import java.io.File

/**
 * Oplus and Shizuku first (true both-sides when privileged).
 * Otherwise classic mic+call mix. Dual AudioRecord is last because it used to
 * report success while writing silence and blocked the mic path.
 */
class SmartCaptureEngine(private val context: Context) {

    private val oplus = OplusRecorderBridge(context)
    private val shizuku = ShizukuEnhancedRecorder(context)
    private val dual = GoogleStyleDualCapture(context)
    private val classic = CallRecorder(context)

    private var active: Backend = Backend.NONE

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

        val offline = forceOffline || context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_OFFLINE_ONLY, false)

        val mode = try {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        } catch (_: Exception) {
            AudioManager.MODE_NORMAL
        }
        Log.i(TAG, "start number=$phoneNumber offline=$offline audioMode=$mode")

        if (oplus.isAvailable() && oplus.start(phoneNumber)) {
            active = Backend.OPLUS
            return Result(true, Backend.OPLUS, "Oplus")
        }

        if (!offline && shizuku.isAvailable() && shizuku.start(phoneNumber)) {
            active = Backend.SHIZUKU
            val strat = shizuku.lastStrategyLabel() ?: "shell"
            return Result(true, Backend.SHIZUKU, "Shizuku $strat")
        }

        if (classic.start(phoneNumber)) {
            active = Backend.CLASSIC
            return Result(true, Backend.CLASSIC, "Classic ${classic.activeLabel}")
        }

        if (dual.start(phoneNumber)) {
            active = Backend.DUAL
            return Result(true, Backend.DUAL, "Dual fallback")
        }

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
        Log.i(TAG, "stop backend=$used bytes=${file?.length()}")
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
