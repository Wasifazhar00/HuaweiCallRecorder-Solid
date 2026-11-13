package com.solid.callrecorder.recorder

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Offline classic recorder.
 * One MediaRecorder only (a second call source was silencing the mic).
 * No audio-focus request (that ducked the call, including on speaker).
 * Files go to app storage so history can open them.
 * Speaker is turned on only when the OS will not give us call audio,
 * then restored when the recording stops.
 */
class CallRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var audioManager: AudioManager? = null
    private var outFile: File? = null
    private var savedMode: Int? = null
    private var savedSpeaker: Boolean? = null

    var isRecording = false
        private set
    var activeSource: Int = MediaRecorder.AudioSource.MIC
        private set
    var activeLabel: String = "MIC"
        private set

    fun start(phoneNumber: String? = null): Boolean {
        if (isRecording) return false
        val dir = RecordingStorage.safeAppDir(context)
        val prefix = phoneNumber?.substringBefore('_')?.takeIf {
            it.equals("In", true) || it.equals("Out", true)
        } ?: "Call"
        val number = phoneNumber?.substringAfter('_', phoneNumber ?: "unknown")
        outFile = File(dir, RecordingStorage.newFileName(prefix, number, "m4a"))

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val source = pickSource()
        activeSource = source
        activeLabel = labelOf(source)
        prepareRoute(forceSpeaker = source == MediaRecorder.AudioSource.MIC)

        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = mr
            mr.setAudioSource(source)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(128_000)
            mr.setOutputFile(outFile!!.absolutePath)
            mr.prepare()
            mr.start()
            isRecording = true
            Log.i(TAG, "classic $activeLabel speaker=${audioManager?.isSpeakerphoneOn} file=${outFile?.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start ${labelOf(source)} failed: ${e.message}")
            releaseRecorder()
            if (source != MediaRecorder.AudioSource.MIC && startMicFallback()) {
                true
            } else {
                restoreRoute()
                false
            }
        }
    }

    private fun startMicFallback(): Boolean {
        return try {
            prepareRoute(forceSpeaker = true)
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = mr
            activeSource = MediaRecorder.AudioSource.MIC
            activeLabel = "MIC"
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(128_000)
            mr.setOutputFile(outFile!!.absolutePath)
            mr.prepare()
            mr.start()
            isRecording = true
            Log.i(TAG, "classic fallback MIC")
            true
        } catch (e: Exception) {
            Log.e(TAG, "mic fallback", e)
            releaseRecorder()
            false
        }
    }

    /**
     * Prefer a source that already contains both sides.
     * VOICE_CALL on a normal app usually throws, so we fall through to the mic.
     */
    private fun pickSource(): Int {
        val candidates = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in candidates) {
            if (sourceOpens(source)) return source
        }
        return MediaRecorder.AudioSource.MIC
    }

    private fun sourceOpens(source: Int): Boolean {
        var mr: MediaRecorder? = null
        val probe = File(context.cacheDir, "probe_$source.m4a")
        return try {
            mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(source)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setOutputFile(probe.absolutePath)
            mr.prepare()
            mr.start()
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                mr?.stop()
            } catch (_: Exception) {
            }
            try {
                mr?.release()
            } catch (_: Exception) {
            }
            probe.delete()
        }
    }

    private fun prepareRoute(forceSpeaker: Boolean) {
        val am = audioManager ?: return
        try {
            if (savedMode == null) savedMode = am.mode
            if (savedSpeaker == null) savedSpeaker = am.isSpeakerphoneOn
            if (am.mode == AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (forceSpeaker && !am.isSpeakerphoneOn) {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "route ${e.message}")
        }
    }

    private fun restoreRoute() {
        val am = audioManager
        try {
            savedSpeaker?.let {
                @Suppress("DEPRECATION")
                am?.isSpeakerphoneOn = it
            }
            savedMode?.let { am?.mode = it }
        } catch (_: Exception) {
        }
        savedSpeaker = null
        savedMode = null
    }

    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
        releaseRecorder()
        restoreRoute()
        val f = outFile
        outFile = null
        return f?.takeIf { it.exists() && it.length() > 500 }
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
    }

    private fun labelOf(source: Int) = when (source) {
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMM"
        else -> "MIC"
    }

    companion object {
        private const val TAG = "CallRecorder"
    }
}

interface EnhancedRecorder {
    fun isAvailable(): Boolean
    fun start(phoneNumber: String?): Boolean
    fun stop(): File?
}
