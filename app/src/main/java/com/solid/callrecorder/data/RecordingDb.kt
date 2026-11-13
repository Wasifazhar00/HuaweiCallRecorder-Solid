package com.solid.callrecorder.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class RecordingEntry(
    val id: Long,
    val path: String,
    val number: String,
    val createdAt: Long,
    val durationMs: Long,
    val backend: String,
    val sizeBytes: Long
)

/** Mirrors Google Dialer call_recording_info storage model (simplified). */
class RecordingDb(context: Context) : SQLiteOpenHelper(context, "call_recording_info.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE call_recording_info (
              _id INTEGER PRIMARY KEY AUTOINCREMENT,
              path TEXT NOT NULL,
              number TEXT,
              call_creation_time_millis INTEGER NOT NULL,
              duration_ms INTEGER DEFAULT 0,
              backend TEXT,
              size_bytes INTEGER DEFAULT 0,
              extras TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun insert(
        path: String,
        number: String?,
        backend: String,
        durationMs: Long = 0
    ): Long {
        val f = File(path)
        val cv = ContentValues().apply {
            put("path", path)
            put("number", number ?: "")
            put("call_creation_time_millis", System.currentTimeMillis())
            put("duration_ms", durationMs)
            put("backend", backend)
            put("size_bytes", if (f.exists()) f.length() else 0L)
        }
        return writableDatabase.insert("call_recording_info", null, cv)
    }

    fun listAll(): List<RecordingEntry> {
        val out = mutableListOf<RecordingEntry>()
        readableDatabase.query(
            "call_recording_info", null, null, null, null, null,
            "call_creation_time_millis DESC"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iPath = c.getColumnIndexOrThrow("path")
            val iNum = c.getColumnIndexOrThrow("number")
            val iTime = c.getColumnIndexOrThrow("call_creation_time_millis")
            val iDur = c.getColumnIndexOrThrow("duration_ms")
            val iBack = c.getColumnIndexOrThrow("backend")
            val iSize = c.getColumnIndexOrThrow("size_bytes")
            while (c.moveToNext()) {
                out.add(
                    RecordingEntry(
                        c.getLong(iId),
                        c.getString(iPath),
                        c.getString(iNum) ?: "",
                        c.getLong(iTime),
                        c.getLong(iDur),
                        c.getString(iBack) ?: "",
                        c.getLong(iSize)
                    )
                )
            }
        }
        return out
    }

    fun deleteId(id: Long): Boolean {
        val row = readableDatabase.query(
            "call_recording_info", arrayOf("path"), "_id=?", arrayOf(id.toString()),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        row?.let { File(it).delete() }
        return writableDatabase.delete("call_recording_info", "_id=?", arrayOf(id.toString())) > 0
    }

    /** Google-style retention: delete older than N days (0 = never). */
    fun deleteOlderThanDays(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val old = mutableListOf<Pair<Long, String>>()
        readableDatabase.query(
            "call_recording_info", arrayOf("_id", "path"),
            "call_creation_time_millis<?", arrayOf(cutoff.toString()),
            null, null, null
        ).use { c ->
            while (c.moveToNext()) old.add(c.getLong(0) to c.getString(1))
        }
        old.forEach { (id, path) ->
            File(path).delete()
            writableDatabase.delete("call_recording_info", "_id=?", arrayOf(id.toString()))
        }
        return old.size
    }

    /** Oplus-style max count: keep newest N. */
    fun enforceMaxCount(max: Int): Int {
        if (max <= 0) return 0
        val all = listAll()
        if (all.size <= max) return 0
        var removed = 0
        all.drop(max).forEach {
            if (deleteId(it.id)) removed++
        }
        return removed
    }
}
