package com.solid.callrecorder.recorder

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Offline classic recorder for incoming and outgoing calls.
 *
 * Uses one microphone recorder. A call-audio source that "starts" but stays
 * silent is not used. Speaker is enabled for the length of the recording so
 * the other person is in the microphone, then the previous route is restored.
 * Audio focus is not requested, because that was ducking the call to silence.
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
        routeForBothSides()

        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = mr
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
            activeLabel = "MIC+speaker"
            Log.i(TAG, "recording ${outFile?.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start", e)
            releaseRecorder()
            restoreRoute()
            false
        }
    }

    private fun routeForBothSides() {
        val am = audioManager ?: return
        try {
            savedMode = am.mode
            savedSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        } catch (e: Exception) {
            Log.w(TAG, "route ${e.message}")
        }
    }

    private fun restoreRoute() {
        val am = audioManager ?: return
        try {
            savedSpeaker?.let {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = it
            }
            savedMode?.let { am.mode = it }
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

    companion object {
        private const val TAG = "CallRecorder"
    }
}

interface EnhancedRecorder {
    fun isAvailable(): Boolean
    fun start(phoneNumber: String?): Boolean
    fun stop(): File?
}
