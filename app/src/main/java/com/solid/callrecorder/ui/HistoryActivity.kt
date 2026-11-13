package com.solid.callrecorder.ui

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.solid.callrecorder.data.RecordingDb
import com.solid.callrecorder.data.RecordingEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var empty: TextView
    private lateinit var db: RecordingDb
    private var items: List<RecordingEntry> = emptyList()
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Recordings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = android.widget.FrameLayout(this)
        listView = ListView(this).apply {
            setPadding(24, 16, 24, 16)
            clipToPadding = false
            dividerHeight = 2
        }
        empty = TextView(this).apply {
            text = "No recordings yet.\nMake a call with auto-record on."
            textSize = 15f
            setPadding(48, 120, 48, 48)
            gravity = android.view.Gravity.CENTER
        }
        root.addView(listView, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(empty, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)

        db = RecordingDb(this)
        reload()

        listView.setOnItemClickListener { _, _, pos, _ ->
            val e = items.getOrNull(pos) ?: return@setOnItemClickListener
            val f = File(e.path)
            if (!f.exists()) {
                Toast.makeText(this, "File missing — removed from list", Toast.LENGTH_SHORT).show()
                db.deleteId(e.id)
                reload()
                return@setOnItemClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(e.number.ifBlank { "Unknown" })
                .setMessage("${e.backend} · ${formatSize(e.sizeBytes)}")
                .setItems(arrayOf("Play", "Share", "Delete")) { _, which ->
                    when (which) {
                        0 -> play(f)
                        1 -> share(f)
                        2 -> {
                            db.deleteId(e.id)
                            reload()
                        }
                    }
                }
                .show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        items = db.listAll()
        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val labels = items.map { e ->
            val whenStr = fmt.format(Date(e.createdAt))
            "${e.number.ifBlank { "unknown" }}\n$whenStr · ${e.backend} · ${formatSize(e.sizeBytes)}"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        empty.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        listView.visibility = if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun play(file: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            Toast.makeText(this, "Playing", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share recording"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
