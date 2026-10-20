package com.hsi.merkrise

import android.app.Activity
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hsi.merkrise.core.getCard
import com.hsi.merkrise.core.loadSeed
import com.hsi.merkrise.core.wordCount

/**
 * First real study screen: the Rust core loads the captured A2 German course
 * (bundled seed.json) and hands back a WordCard; Kotlin shows the German term,
 * English meaning, part of speech, plays the native-speaker audio, and steps to
 * the next word.
 */
class MainActivity : Activity() {
    private var index = 0
    private var total = 0
    private var currentAudio: String? = null
    private var player: MediaPlayer? = null

    private lateinit var progressView: TextView
    private lateinit var termView: TextView
    private lateinit var posView: TextView
    private lateinit var meaningView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load the bundled course data into the Rust core.
        total = try {
            val json = assets.open("seed.json").bufferedReader().use { it.readText() }
            loadSeed(json).toInt()
        } catch (e: Exception) {
            0
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)
        }
        progressView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        termView = TextView(this).apply { textSize = 34f; gravity = Gravity.CENTER }
        posView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        meaningView = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER }

        val playBtn = Button(this).apply {
            text = "▶  Play audio"
            setOnClickListener { playCurrentAudio() }
        }
        val nextBtn = Button(this).apply {
            text = "Next word  ▶"
            setOnClickListener {
                if (total > 0) { index = (index + 1) % total; showCard() }
            }
        }

        root.addView(progressView)
        root.addView(spacer(28))
        root.addView(termView)
        root.addView(posView)
        root.addView(spacer(14))
        root.addView(meaningView)
        root.addView(spacer(40))
        root.addView(playBtn)
        root.addView(spacer(10))
        root.addView(nextBtn)
        setContentView(root)

        showCard()
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(1, h)
    }

    private fun showCard() {
        if (total == 0) {
            progressView.text = ""
            termView.text = "No words loaded"
            posView.text = ""
            meaningView.text = ""
            currentAudio = null
            return
        }
        val card = getCard(index.toUInt()) ?: return
        progressView.text = "Word ${index + 1} / $total   ·   Level ${card.level}"
        termView.text = card.term
        posView.text = card.pos ?: ""
        meaningView.text = card.meaning
        currentAudio = card.audioFile
    }

    private fun playCurrentAudio() {
        val file = currentAudio ?: return
        try {
            player?.release()
            val afd = assets.openFd("audio/$file")
            player = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        } catch (_: Exception) {
            // missing/unreadable audio — ignore for now
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
