package com.solid.callrecorder.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
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
    private lateinit var db: RecordingDb
    private var items: List<RecordingEntry> = emptyList()
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listView = ListView(this).also {
            it.setPadding(24, 24, 24, 24)
            setContentView(it)
        }
        title = "Call recordings"
        db = RecordingDb(this)
        reload()

        listView.setOnItemClickListener { _, _, pos, _ ->
            val e = items.getOrNull(pos) ?: return@setOnItemClickListener
            val f = File(e.path)
            if (!f.exists()) {
                Toast.makeText(this, "File missing", Toast.LENGTH_SHORT).show()
                db.deleteId(e.id)
                reload()
                return@setOnItemClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(e.number.ifBlank { "Unknown" })
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

    private fun reload() {
        items = db.listAll()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val labels = items.map { e ->
            val whenStr = fmt.format(Date(e.createdAt))
            val mb = "%.1f".format(e.sizeBytes / 1024.0 / 1024.0)
            "${e.number.ifBlank { "unknown" }}  ·  $whenStr\n${e.backend}  ·  ${mb} MB"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        if (items.isEmpty()) {
            Toast.makeText(this, "No recordings yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun play(file: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            Toast.makeText(this, "Playing…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Play failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
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
            // Fallback path share
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, file.absolutePath)
                    },
                    "Share path"
                )
            )
        }
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
