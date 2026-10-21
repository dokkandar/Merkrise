package com.hsi.merkrise

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hsi.merkrise.core.AnswerFeedback
import com.hsi.merkrise.core.QKind
import com.hsi.merkrise.core.Question
import com.hsi.merkrise.core.currentQuestion
import com.hsi.merkrise.core.levels
import com.hsi.merkrise.core.loadSeed
import com.hsi.merkrise.core.sessionProgress
import com.hsi.merkrise.core.startSession
import com.hsi.merkrise.core.submitAnswer

/**
 * Merkrise study app. All quiz logic (session building, answer checking,
 * scoring) lives in the Rust core; this Activity only draws the current
 * screen (home -> question -> feedback -> results) and plays audio.
 */
class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var player: MediaPlayer? = null
    private var total = 0
    private val sessionSize = 20u
    private var currentLevel: UInt? = null // null = whole course

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scroll = ScrollView(this).apply {
            // Android 15 draws edge-to-edge; inset below the status/nav bars.
            fitsSystemWindows = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 40, 56, 72)
            // take initial focus so a text field doesn't auto-scroll the prompt away
            isFocusableInTouchMode = true
        }
        scroll.addView(root)
        setContentView(scroll)

        total = try {
            val json = assets.open("seed.json").bufferedReader().use { it.readText() }
            loadSeed(json).toInt()
        } catch (e: Exception) {
            0
        }
        showHome()
    }

    // ---- small view helpers ----
    private fun clear() = root.removeAllViews()
    private fun afterRender() {
        root.requestFocus()               // keep focus off text fields
        scroll.post { scroll.scrollTo(0, 0) } // always show the prompt at top
    }
    private fun gap(h: Int) = View(this).apply { layoutParams = ViewGroup.LayoutParams(1, h) }
    private fun label(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun wideButton(caption: String, onClick: () -> Unit) = Button(this).apply {
        text = caption
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { onClick() }
    }

    // ---- screens ----
    private fun showHome() {
        clear()
        root.gravity = Gravity.TOP
        root.addView(label("Merkrise", 34f, true))
        root.addView(label("A2 German · $total words", 15f))
        root.addView(gap(22))
        root.addView(wideButton("Study all — random $sessionSize") {
            if (total == 0) return@wideButton
            beginSession(null)
        })
        root.addView(gap(22))
        root.addView(label("Chapters", 16f, true))
        root.addView(gap(10))
        for (lv in levels()) {
            root.addView(wideButton("${lv.index}.  ${lv.title}   (${lv.count})") {
                beginSession(lv.index)
            })
            root.addView(gap(8))
        }
        afterRender()
    }

    private fun beginSession(level: UInt?) {
        currentLevel = level
        startSession(level, sessionSize)
        showQuestion()
    }

    private fun showQuestion() {
        val q = currentQuestion() ?: run { showEnd(); return }
        clear()
        root.gravity = Gravity.TOP
        val score = sessionProgress()?.correct ?: 0u
        root.addView(label("Question ${q.position} / ${q.total}        Score $score", 14f))
        root.addView(gap(28))
        val hint = if (q.kind == QKind.MULTIPLE_CHOICE) "German — pick the meaning"
        else "English — type the German"
        root.addView(label(hint, 13f))
        root.addView(gap(8))
        root.addView(label(q.prompt, 30f, true))
        root.addView(gap(12))

        val audio = q.promptAudio
        if (audio != null) {
            root.addView(wideButton("▶  Play audio") { playAudio(audio) })
        }
        root.addView(gap(28))

        when (q.kind) {
            QKind.MULTIPLE_CHOICE ->
                for (choice in q.choices) {
                    root.addView(wideButton(choice) { onAnswered(submitAnswer(choice), choice) })
                    root.addView(gap(10))
                }
            QKind.TYPING -> {
                val input = EditText(this).apply {
                    setHint("type the German word")
                    inputType = InputType.TYPE_CLASS_TEXT
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                root.addView(input)
                root.addView(gap(8))
                val umlauts = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                for (ch in listOf("ä", "ö", "ü", "ß")) {
                    umlauts.addView(Button(this).apply {
                        text = ch; isAllCaps = false
                        setOnClickListener { input.append(ch) }
                    })
                }
                root.addView(umlauts)
                root.addView(gap(14))
                root.addView(wideButton("Check") {
                    val typed = input.text.toString()
                    onAnswered(submitAnswer(typed), typed)
                })
            }
        }
        afterRender()
    }

    private fun onAnswered(fb: AnswerFeedback, chosen: String) {
        clear()
        root.gravity = Gravity.CENTER
        val ok = fb.correct
        root.addView(label(if (ok) "✓  Correct!" else "✗  Not quite", 28f, true).apply {
            setTextColor(if (ok) Color.rgb(27, 120, 40) else Color.rgb(170, 30, 30))
        })
        root.addView(gap(18))
        if (ok) {
            root.addView(label(fb.expected, 22f))
        } else {
            root.addView(label("Correct answer", 14f))
            root.addView(label(fb.expected, 24f, true))
            root.addView(gap(10))
            root.addView(label("you typed: $chosen", 13f))
        }
        root.addView(gap(36))
        root.addView(wideButton(if (fb.finished) "See results" else "Next question") {
            if (fb.finished) showEnd() else showQuestion()
        })
        afterRender()
    }

    private fun showEnd() {
        val p = sessionProgress()
        clear()
        root.gravity = Gravity.CENTER
        root.addView(label("Session complete", 30f, true))
        root.addView(gap(20))
        root.addView(label("You scored", 16f))
        root.addView(label("${p?.correct ?: 0u} / ${p?.total ?: 0u}", 44f, true))
        root.addView(gap(40))
        root.addView(wideButton("Study again") { beginSession(currentLevel) })
        root.addView(gap(10))
        root.addView(wideButton("Home") { showHome() })
        afterRender()
    }

    private fun playAudio(file: String) {
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
            // ignore missing/unreadable audio
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
