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
 * Path aligned with Oplus InCallUI: Music/Recordings/Call Recordings
 */
object RecordingStorage {

    private const val TAG = "RecordingStorage"
    const val RELATIVE_DIR = "Music/Recordings/Call Recordings"

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

    fun publicRecordingsDir(): File? {
        return try {
            val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val dir = File(music, "Recordings/Call Recordings")
            if (!dir.exists()) dir.mkdirs()
            if (dir.exists() && dir.canWrite()) dir else null
        } catch (e: Exception) {
            Log.w(TAG, "publicRecordingsDir: ${e.message}")
            null
        }
    }

    fun safeAppDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val dir = File(base, "Recordings/Call Recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createOutput(context: Context, prefix: String, phoneNumber: String?, ext: String = "m4a"): OutputTarget {
        val name = newFileName(prefix, phoneNumber, ext)
        val mime = if (ext == "wav") "audio/wav" else "audio/mp4"

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
                        return OutputTarget(null, uri, pfd, name)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore: ${e.message}")
            }
        }

        publicRecordingsDir()?.let { dir ->
            return OutputTarget(File(dir, name), null, null, name)
        }

        return OutputTarget(File(safeAppDir(context), name), null, null, name)
    }

    fun finalizeOutput(context: Context, target: OutputTarget?) {
        if (target == null) return
        try {
            target.pfd?.close()
        } catch (_: Exception) {
        }
        if (target.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                context.contentResolver.update(
                    target.uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            } catch (_: Exception) {
            }
        }
    }

    fun humanPath(context: Context): String {
        publicRecordingsDir()?.let { return it.absolutePath }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return RELATIVE_DIR
        return safeAppDir(context).absolutePath
    }
}
