package com.solid.callrecorder.recorder

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Best-effort bridge to ColorOS/Oplus system call recorder.
 *
 * From decompiled com.android.incallui (Oplus):
 *   - Class: com.oplus.media.OplusRecorder
 *   - Used only by privileged InCallUI – may be hidden from 3rd-party apps.
 *
 * If reflection works on the device, we get OEM both-sides quality.
 * Otherwise [isAvailable] is false and classic/Shizuku paths are used.
 */
class OplusRecorderBridge(private val context: Context) {

    private var recorder: Any? = null
    private var outputFile: File? = null

    fun isAvailable(): Boolean {
        return try {
            Class.forName(CLASS)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun start(phoneNumber: String?): Boolean {
        return try {
            val clazz = Class.forName(CLASS)
            val instance = try {
                clazz.getDeclaredConstructor(Context::class.java).newInstance(context)
            } catch (_: Throwable) {
                clazz.getDeclaredConstructor().newInstance()
            }
            recorder = instance

            val dir = RecordingStorage.publicRecordingsDir()
                ?: RecordingStorage.safeAppDir(context)
            val out = File(dir, RecordingStorage.newFileName("Oplus", phoneNumber, "m4a"))
            outputFile = out

            // Common MediaRecorder-like surface on OEM wrappers
            invoke(instance, "setOutputFile", arrayOf(String::class.java), arrayOf(out.absolutePath))
            invoke(instance, "prepare", emptyArray(), emptyArray())
            invoke(instance, "start", emptyArray(), emptyArray())
            Log.i(TAG, "OplusRecorder started → ${out.name}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "OplusRecorder start failed (expected on non-Oplus / non-system): ${e.message}")
            release()
            false
        }
    }

    fun stop(): File? {
        return try {
            recorder?.let {
                invoke(it, "stop", emptyArray(), emptyArray())
                invoke(it, "release", emptyArray(), emptyArray())
            }
            val f = outputFile
            recorder = null
            outputFile = null
            f?.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Throwable) {
            Log.w(TAG, "OplusRecorder stop: ${e.message}")
            release()
            null
        }
    }

    private fun release() {
        try {
            recorder?.let { invoke(it, "release", emptyArray(), emptyArray()) }
        } catch (_: Throwable) {
        }
        recorder = null
        outputFile = null
    }

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, args: Array<Any?>) {
        val m = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == types.size
        } ?: target.javaClass.getMethod(name, *types)
        m.isAccessible = true
        m.invoke(target, *args)
    }

    companion object {
        private const val TAG = "OplusRecorderBridge"
        private const val CLASS = "com.oplus.media.OplusRecorder"
    }
}
