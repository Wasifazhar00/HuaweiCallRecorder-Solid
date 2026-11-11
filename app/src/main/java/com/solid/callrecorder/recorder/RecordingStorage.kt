package com.solid.callrecorder.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prefer public Recordings/CallRecorder (not Android/data).
 * Falls back to app files only if public write is blocked.
 */
object RecordingStorage {

    private const val TAG = "RecordingStorage"
    const val RELATIVE_DIR = "Recordings/CallRecorder"

    data class OutputTarget(
        val file: File?,
        val uri: Uri?,
        val pfd: ParcelFileDescriptor?,
        val displayName: String
    )

    fun newFileName(prefix: String, phoneNumber: String?, ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val num = phoneNumber?.replace(Regex("[^0-9+]"), "")?.takeIf { it.isNotEmpty() } ?: "unknown"
        return "${prefix}_${num}_$ts.$ext"
    }

    /** Public Recordings dir when possible. */
    fun publicRecordingsDir(): File? {
        return try {
            val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
            } else {
                File(Environment.getExternalStorageDirectory(), "Recordings")
            }
            val dir = File(base, "CallRecorder")
            if (!dir.exists()) dir.mkdirs()
            if (dir.exists() && dir.canWrite()) dir else null
        } catch (e: Exception) {
            Log.w(TAG, "publicRecordingsDir: ${e.message}")
            null
        }
    }

    fun safeAppDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val dir = File(base, "CallRecorder")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Create output for MediaRecorder: try MediaStore Recordings, then public File, then app dir.
     */
    fun createOutput(context: Context, prefix: String, phoneNumber: String?, ext: String = "m4a"): OutputTarget {
        val name = newFileName(prefix, phoneNumber, ext)
        val mime = if (ext == "wav") "audio/wav" else "audio/mp4"

        // 1) MediaStore → shows under Recordings / Music apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                    if (pfd != null) {
                        Log.i(TAG, "MediaStore output $RELATIVE_DIR/$name")
                        return OutputTarget(null, uri, pfd, name)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore create failed: ${e.message}")
            }
        }

        // 2) Public Recordings/CallRecorder
        publicRecordingsDir()?.let { dir ->
            val f = File(dir, name)
            Log.i(TAG, "Public file ${f.absolutePath}")
            return OutputTarget(f, null, null, name)
        }

        // 3) App fallback
        val f = File(safeAppDir(context), name)
        Log.i(TAG, "App fallback ${f.absolutePath}")
        return OutputTarget(f, null, null, name)
    }

    fun finalizeOutput(context: Context, target: OutputTarget?) {
        if (target == null) return
        try {
            target.pfd?.close()
        } catch (_: Exception) {
        }
        if (target.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(target.uri, values, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "finalize MediaStore: ${e.message}")
            }
        }
    }

    fun humanPath(context: Context): String {
        publicRecordingsDir()?.let { return it.absolutePath }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return "Recordings/CallRecorder (MediaStore)"
        }
        return safeAppDir(context).absolutePath
    }
}
