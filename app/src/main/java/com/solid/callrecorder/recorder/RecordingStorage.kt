package com.solid.callrecorder.recorder

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reliable save path: app-specific Music/CallRecordings always works.
 * Also tries public Music/CallRecordings when the system allows it.
 */
object RecordingStorage {

    private const val TAG = "RecordingStorage"
    private const val FOLDER = "CallRecordings"

    fun recordingsDir(context: Context): File {
        // 1) Always-writable app folder (primary – must never fail)
        val appMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        val appDir = File(appMusic, FOLDER)
        if (!appDir.exists()) appDir.mkdirs()

        // 2) Optional public Music (visible in gallery/files) when writable
        try {
            val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val publicDir = File(publicMusic, FOLDER)
            if (!publicDir.exists()) publicDir.mkdirs()
            if (publicDir.exists() && publicDir.canWrite()) {
                // Use public if we can; still keep app dir as backup via newFile fallback
                return publicDir
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public Music unavailable: ${e.message}")
        }

        return appDir
    }

    /** Guaranteed writable directory for MediaRecorder. */
    fun safeDir(context: Context): File {
        val appMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        val dir = File(appMusic, FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun newFileName(prefix: String, phoneNumber: String?, ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val num = phoneNumber?.replace(Regex("[^0-9+]"), "")?.takeIf { it.isNotEmpty() } ?: "unknown"
        return "${prefix}_${num}_$ts.$ext"
    }

    fun newFile(context: Context, prefix: String, phoneNumber: String?, ext: String): File {
        // Always write to safeDir so recording never fails due to scoped storage
        return File(safeDir(context), newFileName(prefix, phoneNumber, ext))
    }

    fun indexInMediaStore(context: Context, file: File, mime: String = "audio/mp4") {
        if (!file.exists() || file.length() == 0L) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$FOLDER")
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                    put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Audio.Media.SIZE, file.length())
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf(mime), null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore index failed", e)
        }
    }

    fun humanPath(context: Context): String = safeDir(context).absolutePath
}
