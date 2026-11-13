package com.solid.callrecorder.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.solid.callrecorder.recorder.RecordingStorage
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var empty: TextView
    private var files: List<File> = emptyList()
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Recordings"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(0, 24, 0, 0)
        }
        val header = TextView(this).apply {
            text = "Recordings"
            textSize = 22f
            setPadding(48, 48, 48, 16)
        }
        val hint = TextView(this).apply {
            text = "Tap a file to play it on the media volume."
            textSize = 13f
            setPadding(48, 0, 48, 16)
        }
        listView = ListView(this).apply {
            setPadding(24, 8, 24, 24)
            dividerHeight = 2
        }
        empty = TextView(this).apply {
            text = "No recordings yet.\nAnswer or place a call with auto-record on."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 80, 48, 48)
        }
        root.addView(header)
        root.addView(hint)
        root.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(empty)
        setContentView(root)

        listView.setOnItemClickListener { _, _, pos, _ ->
            val file = files.getOrNull(pos) ?: return@setOnItemClickListener
            if (!file.exists()) {
                Toast.makeText(this, "File is gone", Toast.LENGTH_SHORT).show()
                reload()
                return@setOnItemClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(formatSize(file.length()))
                .setPositiveButton("Play") { _, _ -> play(file) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val dir = RecordingStorage.safeAppDir(this)
        files = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".m4a", true) ||
                f.name.endsWith(".wav", true) ||
                f.name.endsWith(".mp3", true))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val labels = files.map { f ->
            "${f.name}\n${fmt.format(Date(f.lastModified()))} · ${formatSize(f.length())}"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val none = files.isEmpty()
        empty.visibility = if (none) android.view.View.VISIBLE else android.view.View.GONE
        listView.visibility = if (none) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun play(file: File) {
        try {
            player?.release()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            FileInputStream(file).use { input ->
                mp.setDataSource(input.fd)
                mp.prepare()
            }
            mp.start()
            player = mp
            Toast.makeText(this, "Playing ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
