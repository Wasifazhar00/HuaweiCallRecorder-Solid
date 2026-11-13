package com.solid.callrecorder.data

import android.content.Context
import com.solid.callrecorder.ui.MainActivity

object RecordingRepository {

    fun db(context: Context) = RecordingDb(context.applicationContext)

    fun onRecordingFinished(
        context: Context,
        path: String?,
        number: String?,
        backend: String
    ) {
        if (path.isNullOrBlank()) return
        try {
            val database = db(context)
            database.insert(path, number, backend)
            applyRetention(context)
        } catch (_: Exception) {
        }
    }

    fun applyRetention(context: Context) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val days = prefs.getInt(MainActivity.KEY_RETENTION_DAYS, 30)
        val max = prefs.getInt(MainActivity.KEY_MAX_RECORDINGS, 200)
        val database = db(context)
        database.deleteOlderThanDays(days)
        database.enforceMaxCount(max)
    }
}
