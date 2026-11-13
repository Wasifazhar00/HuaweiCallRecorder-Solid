package com.solid.callrecorder.recorder

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * Reflection bridge matching Oplus InCallUI audit:
 *  - setAudioSource(4) = VOICE_CALL
 *  - sample rate 16000
 *  - vendor format/encoder when present, else MediaRecorder-like API
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
            // Audit: Oplus MP3 path uses .mp3; fall back name still fine
            val out = File(dir, RecordingStorage.newFileName("Oplus", phoneNumber, "mp3"))
            outputFile = out

            // Audio source 4 = VOICE_CALL (audit)
            invokeQuiet(instance, "setAudioSource", MediaRecorder.AudioSource.VOICE_CALL)

            // Vendor values from audit when accepted; ignore failures
            invokeQuiet(instance, "setOutputFormat", 9)
            invokeQuiet(instance, "setAudioEncoder", 6)
            invokeQuiet(instance, "setAudioSamplingRate", 16_000)
            invokeQuiet(instance, "setAudioEncodingBitRate", 24_000)
            invokeQuiet(instance, "setAudioChannels", 1)

            invokeQuiet(instance, "setOutputFile", out.absolutePath)
            invoke(instance, "prepare")
            invoke(instance, "start")
            Log.i(TAG, "OplusRecorder started ${out.name}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "OplusRecorder start failed: ${e.message}")
            release()
            false
        }
    }

    fun stop(): File? {
        return try {
            recorder?.let {
                invokeQuiet(it, "stop")
                invokeQuiet(it, "release")
            }
            val f = outputFile
            recorder = null
            outputFile = null
            f?.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Throwable) {
            Log.w(TAG, "stop: ${e.message}")
            release()
            null
        }
    }

    private fun release() {
        try {
            recorder?.let { invokeQuiet(it, "release") }
        } catch (_: Throwable) {
        }
        recorder = null
        outputFile = null
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val types = args.map {
            when (it) {
                is Int -> Int::class.javaPrimitiveType!!
                is String -> String::class.java
                else -> it?.javaClass ?: Any::class.java
            }
        }.toTypedArray()
        val m = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: target.javaClass.getMethod(name, *types)
        m.isAccessible = true
        m.invoke(target, *args)
    }

    private fun invokeQuiet(target: Any, name: String, vararg args: Any?) {
        try {
            invoke(target, name, *args)
        } catch (e: Throwable) {
            Log.d(TAG, "$name skipped: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OplusRecorderBridge"
        private const val CLASS = "com.oplus.media.OplusRecorder"
    }
}
