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
