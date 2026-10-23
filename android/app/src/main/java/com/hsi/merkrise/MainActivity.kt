package com.hsi.merkrise

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.FrameLayout
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.hsi.merkrise.core.AnswerFeedback
import com.hsi.merkrise.core.LevelInfo
import com.hsi.merkrise.core.QKind
import com.hsi.merkrise.core.Question
import com.hsi.merkrise.core.SessionProgress
import com.hsi.merkrise.core.Stage
import com.hsi.merkrise.core.WordRow
import com.hsi.merkrise.core.currentQuestion
import com.hsi.merkrise.core.exportState
import com.hsi.merkrise.core.getCard
import com.hsi.merkrise.core.getPace
import com.hsi.merkrise.core.importState
import com.hsi.merkrise.core.levelWords
import com.hsi.merkrise.core.levels
import com.hsi.merkrise.core.loadSeed
import com.hsi.merkrise.core.markKnown
import com.hsi.merkrise.core.markMistakes
import com.hsi.merkrise.core.rateEasy
import com.hsi.merkrise.core.resetProgress
import com.hsi.merkrise.core.sessionProgress
import com.hsi.merkrise.core.setPace
import com.hsi.merkrise.core.setStarred
import com.hsi.merkrise.core.startFastSession
import com.hsi.merkrise.core.startLearnNew
import com.hsi.merkrise.core.startSession
import com.hsi.merkrise.core.stats
import com.hsi.merkrise.core.submitAnswer
import com.hsi.merkrise.core.wandHint
import java.io.File
import kotlin.math.roundToInt

/**
 * Merkrise study app. All learning logic (stages, SM-2 scheduling, session
 * building, answer checking, scoring, stats) lives in the Rust core; this
 * Activity draws the current screen, plays audio and saves the core's state.
 */
class MainActivity : Activity() {
    private lateinit var frame: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var prefs: SharedPreferences
    private var player: MediaPlayer? = null
    private var sfx: SoundPool? = null
    private var correctSfx = 0
    private var wrongSfx = 0
    private val ui = Handler(Looper.getMainLooper())
    private var pendingWordAudio: Runnable? = null
    private var currentLevel: UInt? = null // null = whole course
    private var currentTitle = COURSE
    private var back: (() -> Unit)? = null // what the system Back button does on this screen
    private var chromeOn = true            // header + bottom bar shown
    private var imeBottom = 0
    private var inSession = false   // a card or its feedback is on screen
    private var typingActive = false // the native keyboard is wanted on this screen
    private var openTopic: String? = null // expanded topic on the Chapters screen

    // Fast review: timed multiple-choice cards. The deadline is checked against elapsedRealtime on every
    // frame (not with a ValueAnimator, which jumps to its end when the system animation scale is 0).
    private var fastMode = false
    private var learnNew = false         // "Learn new": unseen words only, intro then three tasks
    private val learnScored = mutableMapOf<UInt, Int>()  // Learn new: scored tasks per word
    private val learnDone = mutableSetOf<UInt>()          // Learn new: words whose 3 tasks are done
    private var learnWordTotal = 0                        // Learn new: words in this session
    private var cardDone = false         // this card was answered or timed out: ignore further taps
    private var timed: Question? = null  // the timed card on screen, null otherwise
    private var timerTotalMs = 0L
    private var timerEndsAt = 0L
    private var timerRemainingMs = 0L    // kept while the app is paused
    private var timerBar: View? = null
    private var timerLabel: TextView? = null
    private val tick = Runnable { onTick() }
    private var insetTop = 0
    private var insetBottom = 0

    private val stateFile by lazy { File(filesDir, "progress.json") }
    private val sessionSize get() = prefs.getInt("session_size", 20)
    private val autoplay get() = prefs.getBoolean("autoplay", true)
    private val userName get() = prefs.getString("name", null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("merkrise", MODE_PRIVATE)
        sfx = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        correctSfx = sfx!!.load(this, R.raw.correct, 1)
        wrongSfx = sfx!!.load(this, R.raw.wrong, 1)

        header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = corners(SURFACE, 0, 24)
            addView(logo())
            addView(View(context), lp(0, 1, 1f))
            addView(icon(R.drawable.ic_chart, YELLOW, 30).apply {
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener { showStats() }
            })
            addView(icon(R.drawable.ic_person, KEY, 40).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = oval(BG)
                layoutParams = lp(dp(40), dp(40)).apply { marginStart = dp(12) }
                setOnClickListener { showProfile() }
            })
        }
        bottomBar = LinearLayout(this).apply {
            background = corners(SURFACE, 24, 0)
            addView(navItem("Home", R.drawable.ic_home) { showHome() }, lp(0, WRAP, 1f))
            addView(navItem("Setting", R.drawable.ic_settings) { showSettings() }, lp(0, WRAP, 1f))
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // take initial focus so a text field doesn't auto-scroll the prompt away
            isFocusableInTouchMode = true
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            addView(header, lp(MATCH, WRAP))
            addView(scroll, lp(MATCH, 0, 1f))
            addView(bottomBar, lp(MATCH, WRAP))
        }
        applyInsets(0, 0, 0)
        @Suppress("DEPRECATION")
        frame.setOnApplyWindowInsetsListener { _, ins ->
            // Android 15 draws edge-to-edge: the bars paint under the system bars, and
            // the frame shrinks above the keyboard (the stable insets exclude the IME).
            val ime = (ins.systemWindowInsetBottom - ins.stableInsetBottom).coerceAtLeast(0)
            applyInsets(maxOf(ins.stableInsetTop, ins.systemWindowInsetTop), ins.stableInsetBottom, ime)
            ins
        }
        setContentView(frame)

        try {
            val json = assets.open("seed.json").bufferedReader().use { it.readText() }
            loadSeed(json)
            if (stateFile.exists()) importState(stateFile.readText())
        } catch (e: Exception) {
            // an unreadable state file just means we start from the imported Memrise progress
        }
        val fresh = savedInstanceState == null &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        val startDue = consumeStartDue(intent) && fresh // always drop the extra, act only on a fresh launch
        when {
            userName == null -> showWelcome()
            startDue -> beginSession(null, COURSE)
            else -> showHome()
        }
    }

    override fun onResume() {
        super.onResume()
        // opening or resuming the app counts as activity: no reminder for the next 4 h
        Reminders.rearm(this, studying = true)
        // continue a timed card with the time that was left (never a fresh clock, never an instant time-out)
        if (timed != null && !cardDone) startTimer(timerRemainingMs.coerceAtLeast(500))
    }

    override fun onPause() {
        super.onPause()
        if (timed != null && !cardDone) {
            stopTimer()
            timerRemainingMs = (timerEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        }
    }

    /** The reminder notification asks for a session of due words. */
    private fun consumeStartDue(i: Intent?): Boolean {
        val asked = i?.getBooleanExtra(Reminders.EXTRA_START_DUE, false) == true
        i?.removeExtra(Reminders.EXTRA_START_DUE)
        return asked
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // a tap during a session only brings the app to the front; it never replaces the session
        if (consumeStartDue(intent) && !inSession && userName != null) beginSession(null, COURSE)
    }

    private fun applyInsets(top: Int, nav: Int, ime: Int) {
        insetTop = top; insetBottom = nav; imeBottom = ime
        header.setPadding(dp(24), dp(14) + top, dp(24), dp(14))
        bottomBar.setPadding(dp(24), 0, dp(24), nav)
        bottomBar.visibility = if (ime > 0 || !chromeOn) View.GONE else View.VISIBLE
        frame.setPadding(0, 0, 0, ime)
        body.setPadding(dp(24), dp(20) + if (chromeOn) 0 else top, dp(24), dp(24) + if (chromeOn) 0 else nav)
    }

    private fun navItem(label: String, icon: Int, onClick: () -> Unit) = text(label, 12f, KEY, LIGHT).apply {
        setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
        compoundDrawableTintList = ColorStateList.valueOf(KEY)
        compoundDrawablePadding = dp(2)
        setPadding(0, dp(10), 0, dp(8))
        setOnClickListener { onClick() }
    }

    // ---- state + clock ----
    /** Unix seconds shifted to local time: the core uses it for due dates and streak days. */
    private fun now(): Long = Reminders.coreNow()

    private fun save() {
        try {
            val tmp = File(filesDir, "progress.json.tmp")
            tmp.writeText(exportState())
            tmp.renameTo(stateFile)
        } catch (_: Exception) {
            // progress is best-effort; the next answer saves again
        }
        Reminders.rearm(this, studying = true)
    }

    private fun toast(s: String, long: Boolean = false) =
        Toast.makeText(this, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    // ---- screen scaffolding ----
    private fun screen(gravity: Int = Gravity.TOP, chrome: Boolean = true, onBack: (() -> Unit)?) {
        cancelPendingAudio()
        typingActive = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(body.windowToken, 0)
        back = onBack
        chromeOn = chrome
        inSession = false
        stopTimer()
        timed = null
        header.visibility = if (chrome) View.VISIBLE else View.GONE
        applyInsets(insetTop, insetBottom, imeBottom)
        body.removeAllViews()
        body.gravity = gravity
    }

    private fun afterRender() {
        if (!typingActive) body.requestFocus() // keep focus off text fields
        scroll.post { scroll.scrollTo(0, 0) }  // always show the prompt at top
    }

    private fun title(s: String, sub: String? = null) {
        body.addView(text(s, 18f, TEXT, MEDIUM))
        sub?.let { body.addView(text(it, 13f, MUTED)) }
        body.addView(rule())
        body.addView(gap(16))
    }

    private fun tiles(vararg items: Pair<String, String>) = LinearLayout(this).apply {
        layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(8) }
        for ((value, label) in items) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(SURFACE, 18)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                addView(text(value, 22f, YELLOW, BLACK))
                addView(text(label, 12f, MUTED))
            }, lp(0, WRAP, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    private fun speaker(file: String, size: Int = 64) = ImageView(this).apply {
        setImageResource(R.drawable.ic_speaker)
        background = oval(YELLOW)
        elevation = dp(3).toFloat()
        setPadding(dp(size / 4), dp(size / 4), dp(size / 4), dp(size / 4))
        layoutParams = lp(dp(size), dp(size)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        setOnClickListener { playAudio(file) }
    }

    /** Egg photos are the progress indicator: image 1 at the start, 7 only at the 80 % finish line. */
    private fun egg(index: Int, size: Int = 56) = ImageView(this).apply {
        setImageResource(EGGS[index - 1])
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Progress $index of 7"
        layoutParams = lp(dp(size), dp(size))
    }

    /** Correct answers move the egg; a miss leaves it where it was. Image 7 needs a finished unit and >= 80 %. */
    private fun eggIndex(correct: Int, total: Int, complete: Boolean): Int {
        if (total <= 0) return 1
        if (complete && correct * 5 >= total * 4) return 7
        val index = 1 + (6f * correct / total).roundToInt()
        return index.coerceIn(1, if (complete) 7 else 6)
    }

    private fun eggFor(q: Question, progress: SessionProgress?): View {
        // The ring is the session progress bar: it advances on every card, right or wrong.
        val cards = (progress?.total ?: 0u).toInt()
        val answered = (progress?.answered ?: 0u).toInt()
        val fraction = if (cards > 0) (answered.toFloat() / cards).coerceIn(0f, 1f) else 0f
        if (q.taskTotal > 0u) {
            // The egg shows the share of the session's words completed.
            val total = learnWordTotal.coerceAtLeast(1)
            val done = learnDone.size.coerceIn(0, total)
            val index = (1 + (6f * done / total).roundToInt()).coerceIn(1, 7)
            return eggBadge(index, fraction)
        }
        val total = (progress?.scored ?: 0u).toInt()
        val correct = (progress?.correct ?: 0u).toInt()
        val complete = progress?.finished == true
        val index = eggIndex(correct, total, complete)
        return eggBadge(index, fraction)
    }

    private fun eggBadge(index: Int, fraction: Float): View {
        val frame = FrameLayout(this).apply {
            layoutParams = lp(dp(152), dp(152))
            contentDescription = "Progress"
        }
        frame.addView(ProgressRing(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setProgress(fraction)
        })
        frame.addView(egg(index, size = 112).apply {
            layoutParams = FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER)
        })
        return frame
    }

    private fun inputField(hint: String, value: String = "") = EditText(this).apply {
        setText(value)
        setHint(hint)
        setHintTextColor(0x99352542.toInt())
        setTextColor(BG)
        textSize = 20f
        typeface = Typeface.create(MEDIUM, Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = rounded(KEY, 28)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        setSingleLine()
    }

    private fun stageEmoji(s: Stage) = when (s) {
        Stage.NEW -> "🥚"
        Stage.MC -> "🐣"
        Stage.REVERSED -> "🐥"
        Stage.AUDIO -> "🌱"
        Stage.TYPING -> "🌿"
        Stage.KNOWN -> "🌸"
    }

    private fun relative(secs: Long): String = when {
        secs <= 0 -> "now"
        secs < 3600 -> "in ${secs / 60 + 1} min"
        secs < 86_400 -> "in ${secs / 3600} h"
        secs < 2 * 86_400 -> "in 1 day"
        else -> "in ${secs / 86_400} days"
    }

    // ---- onboarding ----
    private fun showWelcome() {
        screen(Gravity.CENTER, chrome = false, onBack = null)
        body.addView(logo(64f).apply { gravity = Gravity.CENTER })
        body.addView(gap(8))
        body.addView(text("Your German vocabulary trainer", 14f, MUTED))
        body.addView(gap(40))
        val name = inputField("Your name")
        body.addView(name)
        body.addView(pill("Get started") {
            prefs.edit().putString("name", name.text.toString().trim().ifEmpty { "Learner" }).apply()
            showHome()
        })
        afterRender()
    }

    // ---- home / chapters / chapter detail ----
    private fun showHome() {
        screen(onBack = null)
        val st = stats(now())
        body.addView(text("Hallo, ${userName ?: "Learner"}", 20f, TEXT, MEDIUM))
        body.addView(gap(12))
        body.addView(text("Currently learning", 15f))
        body.addView(rule())
        body.addView(gap(16))
        body.addView(card(
            badge("DE", 64),
            column(
                text("German", 22f, TEXT, MEDIUM, Gravity.START),
                text("from English · A2 Beyond Beginner", 13f, MUTED, REGULAR, Gravity.START),
                segmentedBar(st.known.toInt(), st.learning.toInt(), st.totalWords.toInt(), 6, 10),
                text("${st.known} of ${st.totalWords} learnt", 12f, MUTED, REGULAR, Gravity.START)
                    .apply { setPadding(0, dp(4), 0, 0) },
            ),
        ).apply { setOnClickListener { showChapters() } })
        body.addView(tiles(
            "${st.dueToday}" to "due today",
            "${st.streakDays}" to "day streak",
            "${st.learning}" to "learning",
        ))
        body.addView(pill("Start session") { beginSession(null, COURSE) })
        body.addView(text("Due words first, then new ones — up to $sessionSize", 12f, MUTED)
            .apply { setPadding(0, dp(8), 0, dp(8)) })
        body.addView(pill("Chapters", SURFACE, TEXT) { showChapters() })
        body.addView(pill("⚡  Fast review", SURFACE, TEXT) { beginFast(null, COURSE) })
        body.addView(text("12 timed questions, getting faster", 12f, MUTED)
            .apply { setPadding(0, dp(8), 0, dp(8)) })
        afterRender()
    }

    private fun showChapters() = renderLessonList(null, false)

    /** The jump list: every lesson grouped by topic, the current topic open and its lesson highlighted. */
    private fun showLessonPicker(current: LevelInfo) {
        openTopic = current.topic
        renderLessonList(current, true)
    }

    /** All lessons grouped by topic (accordion). [current] != null = the jump list opened from a lesson. */
    private fun renderLessonList(current: LevelInfo?, focusCurrent: Boolean) {
        screen { if (current == null) showHome() else showChapter(current) }
        val topics = LinkedHashMap<String, MutableList<LevelInfo>>()
        for (lv in levels()) topics.getOrPut(lv.topic) { mutableListOf() }.add(lv)
        title(
            if (current == null) "German from English" else "Jump to a lesson",
            "${topics.values.sumOf { it.size }} lessons · ${topics.size} topics",
        )
        var focus: View? = null
        for ((topic, lessons) in topics) {
            body.addView(topicCard(topic, lessons) { renderLessonList(current, false) })
            if (topic != openTopic) continue
            for (lv in lessons) {
                val here = lv.index == current?.index
                val row = chapterRow(lv, here).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(20) }
                if (here) focus = row
                body.addView(row)
            }
        }
        val target = focus
        if (focusCurrent && target != null) {
            // scroll the current lesson into view once the list has been laid out
            body.requestFocus()
            target.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int,
                ) {
                    v.removeOnLayoutChangeListener(this)
                    scroll.scrollTo(0, (v.top - dp(160)).coerceAtLeast(0))
                }
            })
        } else {
            afterRender()
        }
    }

    private fun topicCard(topic: String, lessons: List<LevelInfo>, rerender: () -> Unit): View {
        val known = lessons.sumOf { it.known.toInt() }
        val learning = lessons.sumOf { it.learning.toInt() }
        val count = lessons.sumOf { it.count.toInt() }
        val open = topic == openTopic
        return card(
            badge("${lessons.size}", 44),
            column(
                text(topic, 17f, TEXT, MEDIUM, Gravity.START),
                text("${lessons.size} lessons · $known / $count learnt", 13f, MUTED, REGULAR, Gravity.START),
                segmentedBar(known, learning, count, 4, 8),
            ),
            text("›", 26f, YELLOW).apply {
                setPadding(dp(12), 0, 0, 0)
                rotation = if (open) 90f else 0f
            },
        ).apply {
            setOnClickListener {
                val y = scroll.scrollY // keep the list where it is while a topic opens/closes
                openTopic = if (open) null else topic
                rerender()
                scroll.post { scroll.scrollTo(0, y) }
            }
        }
    }

    private fun chapterRow(lv: LevelInfo, highlighted: Boolean = false) = card(
        badge("${lv.index}", 44),
        column(
            text(lv.title, 17f, TEXT, MEDIUM, Gravity.START),
            text("${lv.known} / ${lv.count} learnt", 13f, MUTED, REGULAR, Gravity.START),
            segmentedBar(lv.known.toInt(), lv.learning.toInt(), lv.count.toInt(), 4, 8),
        ),
        text("›", 26f, YELLOW).apply { setPadding(dp(12), 0, 0, 0) },
    ).apply {
        if (highlighted) {
            background = GradientDrawable().apply {
                setColor(SURFACE)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), YELLOW)
            }
        }
        setOnClickListener { showChapter(lv) }
    }

    /** A numbered lesson circle: fill from the lesson's progress, a yellow ring marks the current lesson. */
    private fun lessonCircle(n: Int, l: LevelInfo, current: Boolean): View {
        val (fill, ink) = when {
            l.count > 0u && l.known == l.count -> YELLOW to INK            // every word known
            l.known > 0u || l.learning > 0u -> DIM_YELLOW to SURFACE       // started
            else -> 0xFF4A3A5E.toInt() to MUTED                            // all new
        }
        return text("$n", 15f, ink, MEDIUM).apply {
            layoutParams = lp(dp(48), dp(48)) // a thumb-sized target
            contentDescription = "Lesson $n, ${l.title}"
            background = if (current) {
                LayerDrawable(
                    arrayOf(
                        GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(3), YELLOW) },
                        oval(fill),
                    ),
                ).apply { setLayerInset(1, dp(5), dp(5), dp(5), dp(5)) } // the ring stays visible on a yellow fill
            } else {
                oval(fill)
            }
            if (!current) setOnClickListener { showChapter(l) }
        }
    }

    /** "‹  Lesson title ▾  ›": tap the title for the jump list, the arrows step through the lessons. */
    private fun lessonHeader(lv: LevelInfo, prev: LevelInfo?, next: LevelInfo?): View {
        fun arrow(label: String, target: LevelInfo?) =
            text(label, 30f, if (target != null) YELLOW else 0x44D1D1C5).apply {
                layoutParams = lp(dp(48), dp(48))
                if (target != null) setOnClickListener { showChapter(target) } // dimmed and inert at the ends
            }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP)
            addView(arrow("‹", prev))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                addView(text(lv.title, 18f, TEXT, MEDIUM))
                addView(text("›", 22f, YELLOW).apply {
                    rotation = 90f
                    setPadding(dp(8), 0, 0, 0)
                })
                setOnClickListener { showLessonPicker(lv) }
            }, lp(0, WRAP, 1f))
            addView(arrow("›", next))
        }
    }

    private fun showChapter(lv: LevelInfo) {
        screen { showChapters() }
        openTopic = lv.topic // Back lands on the list with this lesson's topic open
        val words = levelWords(lv.index)
        val now = now()
        val all = levels()
        val at = all.indexOfFirst { it.index == lv.index }
        val known = words.count { it.stage == Stage.KNOWN }
        val learning = words.count { it.stage != Stage.KNOWN && it.stage != Stage.NEW }
        body.addView(lessonHeader(lv, all.getOrNull(at - 1), all.getOrNull(at + 1)))
        body.addView(text("$known / ${words.size} learnt", 13f, MUTED))
        body.addView(rule())
        body.addView(gap(16))
        body.addView(segmentedBar(known, learning, words.size))
        body.addView(pill("Study this chapter") { beginSession(lv.index, lv.title) })
        body.addView(pill("Learn new", SURFACE, TEXT) { beginLearnNew(lv.index, lv.title) })
        body.addView(pill("⚡  Fast review", SURFACE, TEXT) { beginFast(lv.index, lv.title) })
        body.addView(gap(16))

        // lesson circles: one per lesson of this topic; a tap jumps to that lesson
        body.addView(text("Lessons in ${lv.topic}", 13f, MUTED, REGULAR, Gravity.START))
        body.addView(gap(6))
        val lessonGrid = FlowLayout(this, dp(8)).apply {
            background = rounded(SURFACE, 22)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        all.filter { it.topic == lv.topic }.forEachIndexed { i, l ->
            lessonGrid.addView(lessonCircle(i + 1, l, l.index == lv.index))
        }
        body.addView(lessonGrid, lp(MATCH, WRAP))
        body.addView(gap(16))
        body.addView(text("Words", 15f))
        body.addView(rule())
        words.forEach { body.addView(wordRow(it, now)) }
        afterRender()
    }

    private fun reviewLabel(w: WordRow, now: Long) =
        if (w.stage == Stage.NEW) "new" else if (w.due <= now) "due" else relative(w.due - now)

    private fun wordRow(w: WordRow, now: Long) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text((if (w.starred) "★ " else "") + w.term, 16f, TEXT, MEDIUM, Gravity.START))
            addView(text(w.meaning, 13f, MUTED, REGULAR, Gravity.START))
        }, lp(0, WRAP, 1f))
        w.audioFile?.let { f ->
            addView(speaker(f, 36).apply { layoutParams = lp(dp(36), dp(36)).apply { marginStart = dp(8) } })
        }
        addView(text(stageEmoji(w.stage) + " " + reviewLabel(w, now), 12f, MUTED)
            .apply { setPadding(dp(10), 0, 0, 0) })
    }

    // ---- stats / profile / settings ----
    private fun showStats() {
        screen { showHome() }
        val st = stats(now())
        title("Progress")
        body.addView(tiles("${st.known}" to "known", "${st.learning}" to "learning", "${st.newWords}" to "new"))
        body.addView(tiles("${st.dueToday}" to "due today", "${st.streakDays}" to "day streak", "${st.totalWords}" to "words"))
        body.addView(gap(12))
        body.addView(text("Chapters", 15f))
        body.addView(rule())
        for (lv in levels()) {
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(2))
                addView(LinearLayout(context).apply {
                    addView(text("${lv.index}. ${lv.title}", 14f, TEXT, REGULAR, Gravity.START), lp(0, WRAP, 1f))
                    addView(text("${lv.known}/${lv.count}", 13f, MUTED))
                })
                addView(segmentedBar(lv.known.toInt(), lv.learning.toInt(), lv.count.toInt(), 4, 6))
                setOnClickListener { showChapter(lv) }
            })
        }
        afterRender()
    }

    private fun showProfile() {
        screen { showHome() }
        val st = stats(now())
        val name = userName ?: "Learner"
        title("Profile")
        body.addView(badge(name.take(1).uppercase(), 88).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        body.addView(gap(20))
        val field = inputField("Your name", name)
        body.addView(field)
        body.addView(pill("Save name") {
            prefs.edit().putString("name", field.text.toString().trim().ifEmpty { "Learner" }).apply()
            toast("Saved")
            showHome()
        })
        body.addView(gap(20))
        body.addView(tiles("${st.streakDays}" to "day streak", "${st.known}" to "known words"))
        afterRender()
    }

    private fun slider(
        label: String, hint: String, min: Int, max: Int, value: Int,
        show: (Int) -> String, onChange: (Int) -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(12))
        val caption = text("$label: ${show(value)}", 15f, TEXT, MEDIUM, Gravity.START)
        addView(caption)
        addView(SeekBar(context).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(YELLOW)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            thumbTintList = ColorStateList.valueOf(YELLOW)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    caption.text = "$label: ${show(p + min)}"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = onChange(s.progress + min)
            })
        })
        addView(text(hint, 12f, MUTED, REGULAR, Gravity.START))
    }

    private fun showSettings() {
        screen { showHome() }
        val pace = getPace()
        title("Settings")
        body.addView(slider("Words per session", "How many words one session covers.", 5, 50, sessionSize, { "$it" }) {
            prefs.edit().putInt("session_size", it).apply()
        })
        body.addView(slider("New words per session", "Upper limit of new words introduced in one session.", 0, 30,
            pace.newPerSession.toInt(), { "$it" }) {
            setPace(getPace().copy(newPerSession = it.toUInt())); save()
        })
        body.addView(slider("Long-word pace", "Higher: longer terms come back sooner. 0% is plain spaced repetition.", 0, 100,
            (pace.lengthWeight * 100).roundToInt(), { "$it%" }) {
            setPace(getPace().copy(lengthWeight = it / 100f)); save()
        })
        body.addView(slider("Review spacing", "Below ×1.0 reviews come back more often, above ×1.0 less often.", 5, 20,
            (pace.intervalScale * 10).roundToInt(), { "×${it / 10f}" }) {
            setPace(getPace().copy(intervalScale = it / 10f)); save()
        })
        body.addView(Switch(this).apply {
            text = "Play audio automatically"
            setTextColor(TEXT)
            textSize = 15f
            isChecked = autoplay
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(YELLOW, KEY))
            trackTintList = ColorStateList(states, intArrayOf(DIM_YELLOW, TRACK))
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("autoplay", on).apply() }
        })
        body.addView(pill("Send a test reminder", SURFACE, TEXT) { sendTestReminder() })
        body.addView(gap(12))
        body.addView(pill("Reset progress", SURFACE, RED) {
            AlertDialog.Builder(this)
                .setTitle("Reset progress?")
                .setMessage("This forgets everything you studied in this app and goes back to your imported Memrise progress.")
                .setPositiveButton("Reset") { _, _ ->
                    resetProgress(); save(); toast("Progress reset"); showHome()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        afterRender()
    }

    // ---- study session ----
    /** Learn new: only unseen words, each shown with its translation and audio before a four-choice card. */
    private fun beginLearnNew(level: UInt?, title: String) {
        currentLevel = level
        currentTitle = title
        fastMode = false
        learnNew = true
        learnScored.clear()
        learnDone.clear()
        try {
            learnWordTotal = startLearnNew(level, 5u, now()).toInt() // 5 words x 3 tasks
        } catch (e: Exception) {
            learnNew = false
            toast("No new words here")
            return
        }
        showQuestion()
    }

    /** Fast review: 12 timed multiple-choice cards, easy to hard. The core builds the cards and the time limits. */
    private fun beginFast(level: UInt?, title: String) {
        try {
            startFastSession(level, 12u, now())
        } catch (e: Exception) {
            toast("Study some words first")
            return
        }
        currentLevel = level
        currentTitle = title
        fastMode = true
        learnNew = false
        learnScored.clear()
        learnDone.clear()
        learnWordTotal = 0
        showQuestion()
    }

    private fun beginSession(level: UInt?, title: String) {
        currentLevel = level
        currentTitle = title
        fastMode = false
        learnNew = false
        learnScored.clear()
        learnDone.clear()
        learnWordTotal = 0
        try {
            startSession(level, sessionSize.toUInt(), now())
        } catch (e: Exception) {
            toast("Nothing to study here")
            return
        }
        showQuestion()
    }

    private fun leaveSession() = currentLevel?.let { lv -> levels().firstOrNull { it.index == lv } }
        ?.let { showChapter(it) } ?: showHome()

    private fun hintFor(kind: QKind) = when (kind) {
        QKind.PRESENTATION -> "Learn new word"
        QKind.MC, QKind.AUDIO_MC -> "Pick the correct answer"
        QKind.REVERSED -> "Choose the correct translation"
        QKind.TYPING -> "Write the German"
        QKind.MEANING_TYPING -> "Type the English"
        QKind.HEARING -> "Write the hearing"
        QKind.ARRANGE -> "Arrange the sentence"
        QKind.CLOZE -> "Fill the gap"
        QKind.CLOZE_MEANING -> "What does the missing word mean?"
        QKind.CLOZE_TYPING -> "Type the missing word"
        QKind.YES_NO -> "Does the word mean that?"
        QKind.SENTENCE_CHOICE -> "What does it mean?"
    }

    private fun statusStrip(q: Question) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(14) }
        addView(text(stageEmoji(q.stage), 22f).apply {
            background = oval(SURFACE)
            layoutParams = lp(dp(44), dp(44))
        })
        addView(text("×${q.reps}", 12f, MUTED).apply { setPadding(dp(8), 0, 0, 0) })
        addView(View(context), lp(0, 1, 1f))
        if (!(learnNew && q.kind == QKind.PRESENTATION)) {
            addView(chip("already learnt") {
                if (learnNew) learnDone.add(q.word)
                markKnown(q.word); save(); toast("Marked as learnt"); showQuestion()
            })
        }
        if (q.kind != QKind.PRESENTATION) {
            addView(roundButton("👌") { rateEasy(q.word); save(); toast("Marked easy") })
            var starred = q.starred
            addView(roundButton(if (starred) "★" else "😣") { b ->
                starred = !starred
                setStarred(q.word, starred); save()
                b.text = if (starred) "★" else "😣"
                toast(if (starred) "Marked hard" else "Hard mark removed")
            })
        }
    }

    private fun showQuestion() {
        val q = currentQuestion() ?: run { showEnd(); return }
        screen { leaveSession() }
        inSession = true
        cardDone = false
        val progress = sessionProgress()
        val score = progress?.correct ?: 0u
        val timedCard = q.timeLimitMs > 0u
        val heading = when {
            learnNew -> "Learn new · $currentTitle"
            fastMode -> "Fast review · $currentTitle"
            timedCard -> "⚡ Speed round · $currentTitle" // the timed cards at the end of a normal session
            else -> currentTitle
        }
        val line = when {
            q.taskTotal > 0u && q.taskPosition > 0u -> "Task ${q.taskPosition} / ${q.taskTotal}  ·  Score $score"
            q.taskTotal > 0u -> "New word  ·  Score $score"
            else -> "Card ${q.position} / ${q.total}  ·  Score $score"
        }
        body.addView(eggFor(q, progress).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        body.addView(gap(8))
        body.addView(text(heading, 15f, TEXT, MEDIUM, Gravity.CENTER))
        body.addView(text(line, 13f, MUTED, REGULAR, Gravity.CENTER))
        body.addView(gap(10))
        body.addView(segmentedBar(q.position.toInt() - 1, 0, q.total.toInt()))
        // a timed card has no 'already learnt' / easy / hard buttons: a stray tap would cost the player time
        if (timedCard) body.addView(timerRow(q)) else body.addView(statusStrip(q))
        body.addView(gap(20))
        val hint = when {
            timedCard -> "Pick the German phrase, quickly!"
            q.retry -> "Second try — not scored  ·  ${hintFor(q.kind)}"
            else -> hintFor(q.kind)
        }
        body.addView(text(hint, 14f, TEXT, LIGHT))
        body.addView(gap(10))

        when (q.kind) {
            QKind.PRESENTATION -> presentation(q)
            QKind.MC, QKind.REVERSED -> { promptBlock(q); choices(q) }
            QKind.AUDIO_MC -> { q.promptAudio?.let { body.addView(speaker(it, 120)) }; body.addView(gap(24)); choices(q) }
            QKind.CLOZE, QKind.CLOZE_MEANING -> { promptBlock(q); choices(q) }
            QKind.TYPING -> { promptBlock(q); typing(q) }
            QKind.MEANING_TYPING -> { promptBlock(q); typing(q) }
            QKind.HEARING -> { q.promptAudio?.let { body.addView(speaker(it, 120)) }; body.addView(gap(24)); typing(q) }
            QKind.ARRANGE -> { promptBlock(q); arrange(q) }
            QKind.CLOZE_TYPING -> { promptBlock(q); typing(q) }
            QKind.YES_NO -> { promptBlock(q); choices(q) }
            QKind.SENTENCE_CHOICE -> { promptBlock(q); choices(q) }
        }
        afterRender()
        q.promptAudio?.let { if (autoplay) playAudio(it) }
        if (timedCard) startTimer(timerTotalMs)
    }

    /** The draining bar (full -> empty, yellow, red for the last 25%) and the seconds left. */
    private fun timerRow(q: Question): View {
        timed = q
        timerTotalMs = q.timeLimitMs.toLong()
        val fill = View(this).apply {
            background = rounded(YELLOW, 4)
            pivotX = 0f // scale around the left edge: the bar drains towards the left
        }
        val label = text("${(timerTotalMs + 999) / 1000}s", 14f, MUTED, MEDIUM, Gravity.END)
            .apply { minWidth = dp(40); setPadding(dp(10), 0, 0, 0) }
        timerBar = fill
        timerLabel = label
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(10) }
            addView(FrameLayout(context).apply {
                background = rounded(SURFACE, 4)
                addView(fill, FrameLayout.LayoutParams(MATCH, MATCH))
            }, lp(0, dp(8), 1f))
            addView(label)
        }
    }

    private fun startTimer(remainingMs: Long) {
        stopTimer()
        timerEndsAt = SystemClock.elapsedRealtime() + remainingMs
        body.postOnAnimation(tick)
    }

    private fun stopTimer() {
        body.removeCallbacks(tick)
    }

    private fun onTick() {
        val q = timed ?: return
        if (cardDone) return
        val left = timerEndsAt - SystemClock.elapsedRealtime()
        if (left <= 0) {
            timeUp(q)
            return
        }
        val fraction = (left.toFloat() / timerTotalMs).coerceIn(0f, 1f)
        timerBar?.let {
            it.scaleX = fraction
            (it.background as? GradientDrawable)?.setColor(if (fraction <= 0.25f) LIGHT_RED else YELLOW)
        }
        timerLabel?.text = "${(left + 999) / 1000}s"
        body.postOnAnimation(tick)
    }

    /** Time ran out with nothing tapped: exactly a wrong answer. */
    private fun timeUp(q: Question) {
        if (cardDone) return
        cardDone = true
        stopTimer()
        val fb = submitAnswer("")
        save()
        showFeedback(q, fb, "", timedOut = true)
    }

    private fun promptBlock(q: Question) {
        val cloze = q.kind == QKind.CLOZE || q.kind == QKind.CLOZE_MEANING || q.kind == QKind.CLOZE_TYPING || q.kind == QKind.YES_NO || q.kind == QKind.SENTENCE_CHOICE
        body.addView(text(q.prompt, if (cloze) 23f else 30f, TEXT, MEDIUM))
        q.promptAudio?.let { body.addView(gap(14)); body.addView(speaker(it)) }
        body.addView(gap(24))
    }

    private fun presentation(q: Question) {
        body.addView(text(q.prompt, 36f, TEXT, MEDIUM))
        q.promptAudio?.let { body.addView(gap(14)); body.addView(speaker(it)) }
        body.addView(gap(24))
        body.addView(text("English", 14f, TEXT, LIGHT))
        body.addView(text(q.meaning ?: "", 24f))
        q.detail?.let { body.addView(gap(8)); body.addView(text(it, 13f, MUTED)) }
        q.note?.let { body.addView(gap(8)); body.addView(text(it, 13f, MUTED)) }
        body.addView(gap(24))
        body.addView(pill("Continue") {
            val fb = submitAnswer("")
            save()
            if (fb.finished) showEnd() else showQuestion()
        })
        if (learnNew) body.addView(pill("Already known", SURFACE, TEXT) {
            learnDone.add(q.word)
            markKnown(q.word); save(); toast("Marked as known"); showQuestion()
        })
    }

    private fun choices(q: Question) {
        for (pair in q.choices.chunked(2)) {
            val cards = LinearLayout(this)
            for (choice in pair) {
                cards.addView(
                    text(choice, 18f, TEXT, MEDIUM).apply {
                        background = rounded(SURFACE, 22)
                        elevation = dp(6).toFloat()
                        minHeight = dp(96)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setOnClickListener { answer(q, choice) }
                    },
                    lp(0, MATCH, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) },
                )
            }
            if (pair.size == 1) cards.addView(View(this), lp(0, 1, 1f))
            body.addView(cards)
        }
    }

    private fun typing(q: Question) {
        typingActive = true
        val field = inputField("type your answer").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard(this)
                    answer(q, text.toString())
                    true
                } else {
                    false
                }
            }
        }
        body.addView(specialKeys(field))
        body.addView(gap(10))
        body.addView(field)
        body.addView(gap(14))
        body.addView(pill("Continue") {
            hideKeyboard(field)
            answer(q, field.text.toString())
        })
        field.requestFocus()
        field.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            // The native keyboard covers the lower half; scroll the field up so the special
            // characters and Continue stay above the keyboard.
            field.postDelayed({
                if (typingActive) {
                    scroll.smoothScrollTo(0, (field.top - dp(16)).coerceAtLeast(0))
                }
            }, 500)
        }
    }

    private fun specialKeys(field: EditText): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("ä", "ö", "ü", "ß").forEach { ch ->
            row.addView(text(ch, 20f, INK, MEDIUM).apply {
                gravity = Gravity.CENTER
                background = rounded(KEY, 10)
                layoutParams = lp(0, dp(48), 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    insertAtCursor(field, ch)
                    field.requestFocus()
                }
            })
        }
        return row
    }

    private fun insertAtCursor(field: EditText, s: String) {
        val start = minOf(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
        val end = maxOf(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
        field.text.replace(start, end, s)
        field.setSelection(start + s.length)
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun arrange(q: Question) {
        val chosen = mutableListOf<Int>()
        val answerBox = FlowLayout(this, dp(8)).apply {
            background = rounded(SURFACE, 18)
            setPadding(dp(12), dp(12), dp(60), dp(12)) // room for the wand
            minimumHeight = dp(84)
        }
        val bank = FlowLayout(this, dp(8)).apply { setPadding(0, dp(16), 0, dp(8)) }
        fun tile(i: Int) = text(q.tiles[i], 18f, INK, MEDIUM).apply {
            background = rounded(KEY, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        // The bank keeps every tile in its original slot: a picked word leaves an empty hole
        // (INVISIBLE still occupies its space) so the remaining words never jump around.
        val bankTiles = q.tiles.indices.map { i -> tile(i).also { bank.addView(it) } }
        fun render() {
            answerBox.removeAllViews()
            chosen.forEach { i ->
                answerBox.addView(tile(i).apply { setOnClickListener { chosen.remove(i); render() } })
            }
            bankTiles.forEachIndexed { i, v ->
                v.visibility = if (i in chosen) View.INVISIBLE else View.VISIBLE
            }
        }
        bankTiles.forEachIndexed { i, v ->
            v.setOnClickListener { if (i !in chosen) { chosen.add(i); render() } }
        }
        render()
        val wand = icon(R.drawable.ic_wand, INK, 40).apply {
            background = oval(YELLOW)
            contentDescription = "Reveal next word (counts as a miss)"
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(10), dp(10), 0) }
            setOnClickListener {
                if (!prefs.getBoolean("wand_warned", false)) {
                    prefs.edit().putBoolean("wand_warned", true).apply()
                    toast("The wand reveals the answer: this card will count as a miss", long = true)
                }
                // the core reveals the next word(s); the answer line becomes exactly those tiles
                val shown = wandHint(chosen.map { it.toUInt() })
                if (shown.isNotEmpty()) {
                    chosen.clear()
                    chosen.addAll(shown.map { it.toInt() })
                    render()
                }
            }
        }
        body.addView(FrameLayout(this).apply {
            addView(answerBox, FrameLayout.LayoutParams(MATCH, WRAP))
            addView(wand)
        }, lp(MATCH, WRAP))
        body.addView(bank, lp(MATCH, WRAP))
        body.addView(pill("Continue") { answer(q, chosen.joinToString(" ") { q.tiles[it] }) })
    }

    private fun answer(q: Question, input: String) {
        if (cardDone) return // a double tap, or a tap at the instant the time ran out
        cardDone = true
        stopTimer()
        val fb = submitAnswer(input)
        save()
        showFeedback(q, fb, input)
    }

    private fun showFeedback(q: Question, fb: AnswerFeedback, input: String, timedOut: Boolean = false) {
        screen(Gravity.CENTER) { leaveSession() }
        inSession = true
        val ok = fb.correct
        playSfx(ok)
        if (q.taskTotal > 0u && fb.scored) {
            val done = (learnScored[q.word] ?: 0) + 1
            learnScored[q.word] = done
            if (done >= q.taskTotal.toInt()) learnDone.add(q.word)
        }
        body.addView(eggFor(q, sessionProgress()).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        body.addView(gap(16))
        val label = when {
            fb.hinted -> "revealed with the wand"
            timedOut -> "time's up"
            ok -> "correct"
            else -> "not quite"
        }
        body.addView(chip(label, if (ok) GREEN else RED)
            .apply { textSize = 18f; setPadding(dp(28), dp(8), dp(28), dp(8)) })
        body.addView(gap(24))
        if (!ok && !fb.hinted) {
            // show WHAT was wrong: the wrong words (typed kinds) or the wrong choice
            when {
                timedOut -> body.addView(outlined("correct answer", fb.expected, LIGHT_GREEN)) // nothing was picked
                q.kind == QKind.TYPING || q.kind == QKind.MEANING_TYPING || q.kind == QKind.HEARING || q.kind == QKind.ARRANGE || q.kind == QKind.CLOZE_TYPING ->
                    typedMistake(fb.expected, input)
                else -> choiceMistake(fb.expected, input)
            }
        } else {
            if (!ok) {
                body.addView(text("Correct answer", 14f, TEXT, LIGHT))
                body.addView(gap(6))
            }
            body.addView(text(fb.expected, 30f, TEXT, MEDIUM))
            if (!ok && input.isNotBlank()) {
                body.addView(gap(10))
                body.addView(text("you answered: $input", 13f, MUTED))
            }
        }
        if (q.retry) {
            body.addView(gap(10))
            body.addView(text("Second try — not scored", 13f, MUTED))
        }
        if (fb.nextDue > 0) {
            body.addView(gap(14))
            body.addView(text("${stageEmoji(fb.stage)}  next review ${relative(fb.nextDue - now())}", 14f, MUTED))
        }
        val audio = getCard(q.word)?.audioFile
        audio?.let { body.addView(gap(20)); body.addView(speaker(it, 56)) }
        body.addView(gap(32))
        body.addView(pill(if (fb.finished) "See results" else "Continue") {
            if (fb.finished) showEnd() else showQuestion()
        })
        afterRender()
        // Let the correct/wrong sound finish before the German word.
        val wordAudio = audio
        if (autoplay && wordAudio != null && q.promptAudio == null) {
            val run = Runnable {
                pendingWordAudio = null
                playAudio(wordAudio)
            }
            pendingWordAudio = run
            ui.postDelayed(run, if (ok) 1350L else 600L)
        }
        if (!ok) encourage()
    }

    /** A wrong answer gets a friendly nudge in the app's own colours. */
    private fun encourage() {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 24)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(text("Nice try!", 22f, YELLOW, MEDIUM, Gravity.START))
            addView(gap(10))
            addView(text("Mistakes are just part of the learning process.", 15f, MUTED, REGULAR, Gravity.START))
            addView(gap(20))
            addView(pill("Continue") { dialog.dismiss() })
        }
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(), WRAP)
    }

    /** Whitespace split that matches the core's split_whitespace (Unicode spaces included). */
    private fun words(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (c.isWhitespace()) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
            } else cur.append(c)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** The words of [s] joined by spaces, the flagged ones in [color], bold and underlined. */
    private fun marked(s: String, flags: List<Boolean>, color: Int): CharSequence {
        val ws = words(s)
        val out = SpannableStringBuilder()
        ws.forEachIndexed { i, w ->
            if (i > 0) out.append(' ')
            val from = out.length
            out.append(w)
            if (flags.size == ws.size && flags[i]) {
                for (span in listOf(ForegroundColorSpan(color), StyleSpan(Typeface.BOLD), UnderlineSpan())) {
                    out.setSpan(span, from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return out
    }

    private fun typedMistake(expected: String, typed: String) {
        val marks = markMistakes(expected, typed)
        body.addView(text("you answered", 14f, TEXT, LIGHT))
        body.addView(gap(6))
        body.addView(
            if (typed.isBlank()) text("(no answer)", 20f, MUTED)
            else text(marked(typed, marks.typed, LIGHT_RED), 24f, TEXT, MEDIUM),
        )
        body.addView(gap(22))
        body.addView(text("Correct answer", 14f, TEXT, LIGHT))
        body.addView(gap(6))
        body.addView(text(marked(expected, marks.expected, YELLOW), 28f, TEXT, MEDIUM))
    }

    private fun choiceMistake(expected: String, picked: String) {
        body.addView(outlined("your answer", picked, LIGHT_RED))
        body.addView(gap(14))
        body.addView(outlined("correct answer", expected, LIGHT_GREEN))
    }

    private fun outlined(label: String, phrase: String, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp(MATCH, WRAP)
        addView(text(label, 13f, color, MEDIUM))
        addView(text(phrase, 22f, TEXT, MEDIUM).apply {
            background = GradientDrawable().apply {
                setColor(SURFACE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(3), color)
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(6) }
        })
    }

    private fun showEnd() {
        val p = sessionProgress()
        val st = stats(now())
        screen(Gravity.CENTER) { leaveSession() }
        body.addView(text("Session complete", 26f, TEXT, MEDIUM))
        body.addView(gap(20))
        body.addView(text("You scored", 15f, MUTED))
        body.addView(text("${p?.correct ?: 0u} / ${p?.scored ?: 0u}", 56f, YELLOW, BLACK))
        body.addView(gap(8))
        body.addView(text("🔥 ${st.streakDays} day streak  ·  ${st.dueToday} due today", 14f, MUTED))
        body.addView(gap(28))
        if (fastMode) body.addView(pill("⚡  Fast review again") { beginFast(currentLevel, currentTitle) })
        if (learnNew) body.addView(pill("Learn more new words") { beginLearnNew(currentLevel, currentTitle) })
        body.addView(pill(if (fastMode) "Study normally" else "Study again") { beginSession(currentLevel, currentTitle) })
        body.addView(pill("Home", SURFACE, TEXT) { showHome() })
        afterRender()
        maybeAskNotifications()
    }

    private var testReminderPending = false // the permission dialog was opened by the test row

    /** Posts the real reminder right now; asks for the permission (or opens the settings) if it cannot show. */
    private fun sendTestReminder() {
        if (Reminders.canNotify(this)) {
            Reminders.post(this, stats(now()).dueToday)
            toast("Test reminder sent")
        } else if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            testReminderPending = true
            toast("Allow notifications to get reminders")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        } else {
            notificationsOff()
        }
    }

    private fun notificationsOff() {
        toast("Notifications are turned off for Merkrise. Opening the settings.", long = true)
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        Reminders.rearm(this, studying = true) // a granted permission lets the alarm be set again
        if (testReminderPending) {
            testReminderPending = false
            if (granted) sendTestReminder() else notificationsOff()
        }
    }

    /** Android 13+: ask once, after the first finished session (not on first launch). */
    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun playSfx(correct: Boolean) {
        val id = if (correct) correctSfx else wrongSfx
        // 0.05 is about 5 dB below the previous 0.09 playback volume.
        if (id != 0) sfx?.play(id, 0.05f, 0.05f, 1, 0, 1f)
    }

    private fun cancelPendingAudio() {
        pendingWordAudio?.let { ui.removeCallbacks(it) }
        pendingWordAudio = null
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

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        back?.invoke() ?: super.onBackPressed()
    }

    override fun onDestroy() {
        cancelPendingAudio()
        player?.release()
        player = null
        sfx?.release()
        sfx = null
        super.onDestroy()
    }

    private companion object {
        const val COURSE = "A2 German"
    }
}
