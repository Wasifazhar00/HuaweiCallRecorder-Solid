package com.solid.callrecorder.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves under public Music/CallRecordings when possible (visible in Files app),
 * otherwise app Music folder.
 */
object RecordingStorage {

    private const val TAG = "RecordingStorage"
    private const val FOLDER = "CallRecordings"

    /** Directory for direct File paths (classic + enhanced). */
    fun recordingsDir(context: Context): File {
        // Prefer shared Music so user finds files easily
        val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val publicDir = File(publicMusic, FOLDER)
        try {
            if (!publicDir.exists()) publicDir.mkdirs()
            if (publicDir.exists() && publicDir.canWrite()) {
                return publicDir
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public Music not writable: ${e.message}")
        }

        val appMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val fallback = File(appMusic, FOLDER)
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    fun newFileName(prefix: String, phoneNumber: String?, ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val num = phoneNumber?.replace(Regex("[^0-9+]"), "")?.takeIf { it.isNotEmpty() } ?: "unknown"
        return "${prefix}_${num}_$ts.$ext"
    }

    fun newFile(context: Context, prefix: String, phoneNumber: String?, ext: String): File {
        return File(recordingsDir(context), newFileName(prefix, phoneNumber, ext))
    }

    /**
     * Optional: index AAC/M4A into MediaStore so it appears in music/files scanners.
     */
    fun indexInMediaStore(context: Context, file: File, mime: String = "audio/mp4") {
        if (!file.exists()) return
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
                // Only insert if we did not already write via MediaStore
                val existing = context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    MediaStore.Audio.Media.DISPLAY_NAME + "=?",
                    arrayOf(file.name),
                    null
                )
                val has = existing?.use { it.moveToFirst() } == true
                if (!has) {
                    context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                }
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mime),
                    null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore index failed", e)
        }
    }

    fun humanPath(context: Context): String {
        val dir = recordingsDir(context)
        return dir.absolutePath
    }
}
