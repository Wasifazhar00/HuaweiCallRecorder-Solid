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
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    fun safeAppDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val dir = File(base, "Recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listPlayable(context: Context): List<File> {
        return safeAppDir(context).listFiles { f ->
            f.isFile && (f.name.endsWith(".m4a", true) || f.name.endsWith(".wav", true) || f.name.endsWith(".mp3", true))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** Copies a finished recording into the system music library so any player can open it. */
    fun publishToLibrary(context: Context, file: File): Uri? {
        if (!file.exists() || file.length() < 44) return null
        return try {
            val mime = when {
                file.name.endsWith(".wav", true) -> "audio/wav"
                file.name.endsWith(".mp3", true) -> "audio/mpeg"
                else -> "audio/mp4"
            }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            Log.i(TAG, "published ${file.name} -> $uri")
            uri
        } catch (e: Exception) {
            Log.w(TAG, "publish ${e.message}")
            null
        }
    }

    fun humanPath(context: Context): String = safeAppDir(context).absolutePath

    fun publicRecordingsDir(): File? = null

    fun createOutput(context: Context, prefix: String, phoneNumber: String?, ext: String = "m4a"): OutputTarget {
        val name = newFileName(prefix, phoneNumber, ext)
        return OutputTarget(File(safeAppDir(context), name), null, null, name)
    }

    fun finalizeOutput(context: Context, target: OutputTarget?) {
        target?.file?.let { publishToLibrary(context, it) }
    }
}
