package com.solid.callrecorder.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.solid.callrecorder.recorder.RecordingStorage
import java.io.File

class HistoryActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var seek: SeekBar
    private lateinit var play: Button
    private lateinit var listView: ListView
    private var files: List<File> = emptyList()
    private var player: MediaPlayer? = null
    private var current: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val tick = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null && mp.isPlaying && !userSeeking) {
                seek.max = mp.duration.coerceAtLeast(1)
                seek.progress = mp.currentPosition
            }
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(32, 48, 32, 32)
        }
        title = TextView(this).apply {
            text = "Player"
            textSize = 20f
        }
        seek = SeekBar(this)
        play = Button(this).apply { text = "Play" }
        val external = Button(this).apply { text = "Open in system player" }
        listView = ListView(this)
        root.addView(title)
        root.addView(seek)
        root.addView(play)
        root.addView(external)
        root.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        play.setOnClickListener { toggle() }
        external.setOnClickListener { openExternal() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) { userSeeking = false }
        })
        listView.setOnItemClickListener { _, _, pos, _ ->
            files.getOrNull(pos)?.let { startFile(it) }
        }

        reload()
        intent.getStringExtra("play")?.let { path ->
            val f = File(path)
            if (f.exists()) startFile(f)
        }
        handler.post(tick)
    }

    private fun reload() {
        files = RecordingStorage.listPlayable(this)
        if (files.isEmpty()) {
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("No recordings yet"))
        } else {
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, files.map { it.name })
        }
    }

    private fun toggle() {
        val mp = player
        if (mp == null) {
            (current ?: files.firstOrNull())?.let { startFile(it) }
            return
        }
        if (mp.isPlaying) {
            mp.pause()
            play.text = "Play"
        } else {
            mp.start()
            play.text = "Pause"
        }
    }

    private fun startFile(file: File) {
        try {
            player?.release()
            current = file
            title.text = file.name
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { play.text = "Play" }
            mp.prepare()
            seek.max = mp.duration.coerceAtLeast(1)
            mp.start()
            play.text = "Pause"
            player = mp
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openExternal() {
        val file = current ?: files.firstOrNull() ?: run {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "No system player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        super.onDestroy()
    }
}
