package com.solid.callrecorder.recorder

import android.content.Context
import android.util.Log
import com.solid.callrecorder.ui.MainActivity
import java.io.File

/** Classic mic path first. Privileged paths only when the user turns them on. */
class SmartCaptureEngine(private val context: Context) {

    private val oplus = OplusRecorderBridge(context)
    private val shizuku = ShizukuEnhancedRecorder(context)
    private val classic = CallRecorder(context)

    private var active: Backend = Backend.NONE

    enum class Backend { NONE, OPLUS, SHIZUKU, CLASSIC }

    data class Result(val ok: Boolean, val backend: Backend, val detail: String)

    fun start(phoneNumber: String?, forceOffline: Boolean = false): Result {
        if (active != Backend.NONE) return Result(false, active, "already recording")
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val privileged = !forceOffline &&
            !prefs.getBoolean(MainActivity.KEY_OFFLINE_ONLY, true) &&
            prefs.getBoolean(MainActivity.KEY_PRIVILEGED, false)

        if (privileged && oplus.isAvailable() && oplus.start(phoneNumber)) {
            active = Backend.OPLUS
            return Result(true, Backend.OPLUS, "Oplus")
        }
        if (privileged && shizuku.isAvailable() && shizuku.start(phoneNumber)) {
            active = Backend.SHIZUKU
            return Result(true, Backend.SHIZUKU, "Shizuku ${shizuku.lastStrategyLabel() ?: ""}".trim())
        }
        if (classic.start(phoneNumber)) {
            active = Backend.CLASSIC
            return Result(true, Backend.CLASSIC, "Classic ${classic.activeLabel}")
        }
        Log.e(TAG, "no backend")
        return Result(false, Backend.NONE, "microphone did not start")
    }

    fun stop(): Pair<File?, Backend> {
        val used = active
        val file = try {
            when (active) {
                Backend.OPLUS -> oplus.stop()
                Backend.SHIZUKU -> shizuku.stop()?.also { RecordingStorage.publishToLibrary(context, it) }
                Backend.CLASSIC -> classic.stop()
                Backend.NONE -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop", e)
            null
        }
        active = Backend.NONE
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
