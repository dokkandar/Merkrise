package com.hsi.merkrise

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import com.hsi.merkrise.core.resetProgress
import com.hsi.merkrise.core.sessionProgress
import com.hsi.merkrise.core.setPace
import com.hsi.merkrise.core.startFastSession
import com.hsi.merkrise.core.startLearnNew
import com.hsi.merkrise.core.startSession
import com.hsi.merkrise.core.stats
import com.hsi.merkrise.core.submitAnswer
import com.hsi.merkrise.core.wandHint
import java.io.File
import java.util.Locale
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
    private lateinit var greeting: TextView
    private lateinit var points: TextView
    private lateinit var subtitle: TextView
    private lateinit var menuButton: View
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
    private var chromeOn = true            // header shown
    private var imeBottom = 0
    private var imeUp = false
    private var onIme: ((Boolean) -> Unit)? = null // the typing card rearranges itself around the keyboard
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
    private var pie: PieTimer? = null
    private var timedReveal: ((AnswerFeedback, String) -> Unit)? = null // colours the timed card's options
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

        // Figma header: "⋯" menu, ⌂, logo · "Hello <name>" over the total of correct answers, avatar; subtitle below.
        greeting = text("", 10f, TEXT, REGULAR)
        points = text("", 10f, TEXT, MEDIUM)
        subtitle = text("", 15f, TEXT, MEDIUM)
        menuButton = text("...", 15f, 0xFFFFFFFF.toInt(), BLACK).apply {
            setPadding(dp(15), dp(8), dp(6), dp(8))
            contentDescription = "Menu"
            setOnClickListener { showMenu(it) }
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(menuButton)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_home_outline)
                    contentDescription = "Home"
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    setOnClickListener { showHome() }
                }, lp(dp(26), dp(32)).apply { marginStart = dp(4) })
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_launcher_logo)
                    scaleType = ImageView.ScaleType.FIT_START
                }, lp(dp(59), dp(28)).apply { marginStart = dp(20) })
                addView(View(context), lp(0, 1, 1f))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(greeting)
                    addView(points)
                }, lp(WRAP, WRAP).apply { marginEnd = dp(5) })
                addView(icon(R.drawable.ic_person, KEY, 28).apply {
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = oval(BG)
                    contentDescription = "Profile"
                    setOnClickListener { showProfile() }
                }, lp(dp(28), dp(28)).apply { marginEnd = dp(11) })
            }, lp(MATCH, dp(46)))
            addView(subtitle, lp(MATCH, WRAP).apply { topMargin = dp(6) })
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
        header.setPadding(0, dp(3) + top, 0, dp(4))
        frame.setPadding(0, 0, 0, ime + nav) // no bottom bar any more: keep clear of the navigation bar too
        body.setPadding(dp(12), if (chromeOn) 0 else top, dp(12), dp(24))
        if ((ime > 0) != imeUp) {
            imeUp = ime > 0
            onIme?.invoke(imeUp)
        }
    }

    /** "⋯": the places the bottom bar used to hold, plus the old header's chart icon. */
    private fun showMenu(anchor: View) = PopupMenu(ContextThemeWrapper(this, android.R.style.Theme_Material), anchor).apply {
        menu.add("Chapters")
        menu.add("Stats")
        menu.add("Settings")
        setOnMenuItemClickListener {
            when (it.title?.toString()) {
                "Chapters" -> showChapters()
                "Stats" -> showStats()
                else -> showSettings()
            }
            true
        }
    }.show()

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
    /** [sub] is the header's second line, [top] the gap above the content (dp). */
    private fun screen(
        gravity: Int = Gravity.TOP, chrome: Boolean = true, sub: String = COURSE_NAME, top: Int = 20,
        onBack: (() -> Unit)?,
    ) {
        cancelPendingAudio()
        typingActive = false
        onIme = null
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(body.windowToken, 0)
        back = onBack
        chromeOn = chrome
        inSession = false
        stopTimer()
        timed = null
        pie = null
        timedReveal = null
        header.visibility = if (chrome) View.VISIBLE else View.GONE
        menuButton.visibility = View.VISIBLE
        greeting.text = "Hello ${userName ?: "Learner"}"
        points.text = String.format(Locale.US, "%,d", stats(now()).correctTotal.toLong())
        subtitle.text = sub
        applyInsets(insetTop, insetBottom, imeBottom)
        body.removeAllViews()
        body.gravity = gravity
        if (top > 0) body.addView(gap(top))
    }

    /** A card or its answer: the header names the lesson and drops the "⋯" menu (⌂ still leaves). */
    private fun sessionScreen() {
        screen(sub = if (currentTitle == COURSE) COURSE_NAME else "$COURSE_NAME, $currentTitle", top = 0) { leaveSession() }
        inSession = true
        menuButton.visibility = View.GONE
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

    /** Correct answers move the egg; a miss leaves it where it was. Image 7 needs a finished unit and >= 80 %. */
    private fun eggIndex(correct: Int, total: Int, complete: Boolean): Int {
        if (total <= 0) return 1
        if (complete && correct * 5 >= total * 4) return 7
        val index = 1 + (6f * correct / total).roundToInt()
        return index.coerceIn(1, if (complete) 7 else 6)
    }

    private fun eggIndexFor(q: Question, progress: SessionProgress?): Int {
        if (q.taskTotal > 0u) {
            // Learn new: the egg shows the share of the session's words completed.
            val total = learnWordTotal.coerceAtLeast(1)
            val done = learnDone.size.coerceIn(0, total)
            return (1 + (6f * done / total).roundToInt()).coerceIn(1, 7)
        }
        val total = (progress?.scored ?: 0u).toInt()
        val correct = (progress?.correct ?: 0u).toInt()
        return eggIndex(correct, total, progress?.finished == true)
    }

    /** The egg photo cropped to a 112 dp circle inside a 5 dp yellow ring (the ring no longer shows progress). */
    private fun eggBadge(index: Int) = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(5), RING) }
        contentDescription = "Progress $index of 7"
        addView(ImageView(context).apply {
            setImageResource(EGGS[index - 1])
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = oval(BG)
            clipToOutline = true
        }, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
    }

    /** The yellow megaphone button: plays [audio]; kept invisible (its space too) when there is nothing to play. */
    private fun megaphone(audio: String?, size: Int = 56) = ImageView(this).apply {
        setImageResource(R.drawable.ic_megaphone)
        background = oval(YELLOW)
        val pad = dp(size) * 9 / 56
        setPadding(pad, pad, pad, pad)
        contentDescription = "Play the word"
        if (audio == null) visibility = View.INVISIBLE else setOnClickListener { playAudio(audio) }
    }

    /**
     * The Figma top block of every card and answer screen: "+N" (correct so far) over a "Learned" button at the
     * left, the egg in the middle, the megaphone at the right and, on a card, "answered/total" over a progress bar.
     */
    private fun topBlock(q: Question, progress: SessionProgress?, audio: String?, bar: Boolean, onLearned: (() -> Unit)?): View {
        fun at(g: Int, w: Int, h: Int, top: Int, side: Int = 0) =
            FrameLayout.LayoutParams(w, h, Gravity.TOP or g).apply { topMargin = dp(top); marginStart = side; marginEnd = side }
        return FrameLayout(this).apply {
            layoutParams = lp(MATCH, dp(if (bar) 196 else 145))
            addView(eggBadge(eggIndexFor(q, progress)), at(Gravity.CENTER_HORIZONTAL, dp(122), dp(122), 22))
            addView(text("+${progress?.correct ?: 0u}", 15f, TEXT, MEDIUM), at(Gravity.START, dp(74), WRAP, 35))
            onLearned?.let { f ->
                addView(text("Learned", 15f, KEY, LIGHT).apply {
                    background = rounded(SURFACE, 12)
                    setOnClickListener { f() }
                }, at(Gravity.START, dp(74), dp(24), 73))
            }
            addView(megaphone(audio), at(Gravity.END, dp(56), dp(56), 56))
            if (bar) {
                val total = (progress?.total ?: 0u).toInt()
                val answered = (progress?.answered ?: 0u).toInt().coerceIn(0, total)
                addView(text("$answered/$total", 15f, TEXT, MEDIUM), at(Gravity.END, WRAP, WRAP, 147, dp(26)))
                addView(LinearLayout(context).apply {
                    background = rounded(TRACK, 8)
                    addView(View(context).apply { background = rounded(YELLOW, 8) }, lp(0, MATCH, answered.toFloat()))
                    addView(View(context), lp(0, MATCH, (total - answered).toFloat().coerceAtLeast(if (total == 0) 1f else 0f)))
                }, at(Gravity.START, MATCH, dp(15), 181, dp(24)))
            }
        }
    }

    /** "Learned" = the old "already learnt": the word becomes Known and leaves the session. */
    private fun learned(q: Question, refresh: Boolean) {
        if (learnNew) learnDone.add(q.word)
        markKnown(q.word); save(); toast("Marked as learnt")
        if (refresh) showQuestion()
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
        screen(sub = "Currently Learning", top = 45, onBack = null)
        val st = stats(now())
        fun wide(h: Int, top: Int) = lp(MATCH, h).apply { setMargins(dp(14), dp(top), dp(14), 0) }
        // course card: photo, title, bar, "x of y learnt"; a tap opens the chapters
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(SURFACE)
                val l = dp(58).toFloat(); val r = dp(25).toFloat()
                cornerRadii = floatArrayOf(l, l, r, r, r, r, l, l)
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.course_photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = oval(BG)
                clipToOutline = true
            }, lp(dp(73), dp(73)).apply { marginStart = dp(11) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("German  A2", 22.5f, KEY, MEDIUM))
                addView(text("Beyond Beginner", 10f, KEY, LIGHT))
                addView(LinearLayout(context).apply {
                    setBackgroundColor(BG)
                    addView(View(context).apply { setBackgroundColor(YELLOW) }, lp(0, MATCH, st.known.toFloat()))
                    addView(View(context), lp(0, MATCH, (st.totalWords - st.known).toFloat()))
                }, lp(MATCH, dp(4)).apply { topMargin = dp(8) })
                addView(text("${st.known} of ${st.totalWords} learnt", 10f, KEY, LIGHT).apply { setPadding(0, dp(4), 0, 0) })
            }, lp(0, WRAP, 1f).apply { setMargins(dp(25), 0, dp(35), 0) })
            setOnClickListener { showChapters() }
        }, wide(dp(94), 0))
        body.addView(LinearLayout(this).apply {
            listOf("${st.dueToday}" to "due Today", "${st.streakDays}" to "day Streak", "${st.learning}" to "Learning")
                .forEachIndexed { i, (value, label) ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = rounded(SURFACE, 29)
                        addView(text(value, 25f, KEY, MEDIUM))
                        addView(text(label, 10f, KEY, LIGHT))
                    }, lp(0, MATCH, 1f).apply { if (i > 0) marginStart = dp(9) })
                }
        }, wide(dp(59), 17))
        body.addView(LinearLayout(this).apply {
            fun square(label: String, go: () -> Unit) = text(label, 30f, KEY, MEDIUM).apply {
                background = rounded(SURFACE, 29)
                setOnClickListener { go() }
            }
            addView(square("Classic\nReview") { beginSession(null, COURSE) }, lp(0, MATCH, 1f))
            addView(square("Fast\nReview") { beginFast(null, COURSE) }, lp(0, MATCH, 1f).apply { marginStart = dp(17) })
        }, wide(dp(172), 109))
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

    private fun showQuestion() {
        val q = currentQuestion() ?: run { showEnd(); return }
        sessionScreen()
        cardDone = false
        if (q.timeLimitMs > 0u) {
            timedCard(q)
            return
        }
        val audio = q.promptAudio
        val canMark = !(learnNew && q.kind == QKind.PRESENTATION) // Learn new's intro has its own "Already known"
        val top = topBlock(q, sessionProgress(), audio, bar = true, onLearned = { learned(q, refresh = true) }.takeIf { canMark })
        body.addView(top)
        if (q.retry) body.addView(text("Second try — not scored", 13f, MUTED))
        when (q.kind) {
            QKind.PRESENTATION -> presentation(q)
            QKind.TYPING, QKind.MEANING_TYPING, QKind.CLOZE_TYPING -> typing(q, top, hearing = false)
            QKind.HEARING -> typing(q, top, hearing = true)
            QKind.ARRANGE -> arrange(q)
            else -> { // the choice kinds; an audio-only card has no text prompt (the megaphone asks)
                prompt(q, 46)
                body.addView(gap(if (q.prompt.isBlank()) 30 else 53))
                choices(q, 22.5f) { answer(q, it) }
            }
        }
        afterRender()
        audio?.let { if (autoplay) playAudio(it) }
    }

    /** The question text, [above] dp below what precedes it; nothing for an audio-only card. */
    private fun prompt(q: Question, above: Int) {
        if (q.prompt.isBlank()) return
        body.addView(gap(above))
        body.addView(text(q.prompt, 30f, PROMPT, MEDIUM))
    }

    /** Full-width option cards, one per row. */
    private fun choices(q: Question, size: Float, onPick: (String) -> Unit): List<TextView> = q.choices.map { choice ->
        text(choice, size, TEXT, MEDIUM).apply {
            background = rounded(SURFACE, 25)
            minHeight = dp(80)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onPick(choice) }
            body.addView(this, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    /**
     * Fast review and the speed round (Figma "quick review"): a pie clock, Continue (= skip, a miss), the prompt
     * and the options. The answer shows in place: the wrong pick red, the right option green; Continue goes on.
     */
    private fun timedCard(q: Question) {
        timed = q
        timerTotalMs = q.timeLimitMs.toLong()
        val clock = PieTimer(this).also { pie = it }
        body.addView(FrameLayout(this).apply {
            addView(clock, FrameLayout.LayoutParams(dp(122), dp(122), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(28) })
            addView(megaphone(q.promptAudio), FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END)
                .apply { topMargin = dp(56) })
        }, lp(MATCH, dp(150)))
        if (!fastMode) body.addView(text("⚡ Speed round", 13f, MUTED)) // the timed cards at the end of a normal session
        var result: AnswerFeedback? = null
        body.addView(pill("Continue") {
            val fb = result
            when {
                fb != null -> if (fb.finished) showEnd() else showQuestion()
                !cardDone -> { // skip: an ordinary miss, straight on to the next card
                    cardDone = true
                    stopTimer()
                    val miss = submitAnswer("")
                    save()
                    if (miss.finished) showEnd() else showQuestion()
                }
            }
        }.apply {
            setPadding(dp(6), dp(2), dp(6), dp(2)) // "Continue" at 22.5sp needs ~100 of the 116 dp
            layoutParams = lp(dp(116), WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(19) }
        })
        prompt(q, 20)
        body.addView(gap(10))
        val cards = choices(q, 30f) { picked ->
            if (!cardDone) {
                cardDone = true
                stopTimer()
                val fb = submitAnswer(picked)
                save()
                timedReveal?.invoke(fb, picked)
            }
        }
        timedReveal = { fb, picked ->
            result = fb
            playSfx(fb.correct)
            for (c in cards) {
                val s = c.text.toString()
                if (s == fb.expected) c.background = rounded(PICK_GREEN, 25)
                else if (s == picked) c.background = rounded(PICK_RED, 25)
            }
            sayWordLater(q, fb.correct)
        }
        afterRender()
        startTimer(timerTotalMs)
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
        if (timed == null || cardDone) return
        val left = timerEndsAt - SystemClock.elapsedRealtime()
        if (left <= 0) {
            timeUp()
            return
        }
        pie?.setLeft(left.toFloat() / timerTotalMs)
        body.postOnAnimation(tick)
    }

    /** Time ran out with nothing tapped: exactly a wrong answer; the right option turns green. */
    private fun timeUp() {
        if (cardDone) return
        cardDone = true
        stopTimer()
        pie?.setLeft(0f)
        val fb = submitAnswer("")
        save()
        timedReveal?.invoke(fb, "")
    }

    private fun presentation(q: Question) {
        body.addView(gap(31))
        body.addView(text(q.prompt, 36f, PROMPT, MEDIUM))
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

    /**
     * Typing and Hearing (Figma boards E/F): a big dark answer box over the ü ö ß ä keys; the keyboard's ↵ submits.
     * While the keyboard is up the top block hides, Typing's prompt moves up smaller and Hearing's megaphone
     * sits above the box.
     */
    private fun typing(q: Question, top: View, hearing: Boolean) {
        typingActive = true
        val lead = gap(0).also { body.addView(it) }
        val promptView = if (hearing) null else text(q.prompt, 30f, PROMPT, MEDIUM).also { body.addView(it) }
        val upMegaphone = if (hearing) megaphone(q.promptAudio).also {
            body.addView(it, lp(dp(56), dp(56)).apply {
                gravity = Gravity.END; marginEnd = dp(29); topMargin = dp(17); bottomMargin = dp(14)
            })
        } else null
        if (!hearing) body.addView(gap(40))
        val field = EditText(this).apply {
            hint = "type here ..."
            setHintTextColor(TEXT)
            setTextColor(TEXT)
            textSize = 22.5f
            typeface = Typeface.create(MEDIUM, Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = rounded(SURFACE, 25)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            minHeight = dp(if (hearing) 188 else 144)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false) // long sentences wrap inside the box
            maxLines = 5
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
        body.addView(field, lp(MATCH, WRAP))
        val keysGap = gap(0).also { body.addView(it) }
        body.addView(specialKeys(field))
        fun height(v: View, h: Int) { v.layoutParams = v.layoutParams.apply { height = dp(h) } }
        onIme = { up ->
            top.visibility = if (up) View.GONE else View.VISIBLE
            upMegaphone?.visibility = if (up) View.VISIBLE else View.GONE
            height(lead, if (hearing) (if (up) 0 else 110) else (if (up) 45 else 31))
            promptView?.apply {
                textSize = if (up) 25f else 30f
                typeface = Typeface.create(if (up) REGULAR else MEDIUM, Typeface.NORMAL)
            }
            height(keysGap, if (up) 36 else 61)
            if (up) scroll.post { scroll.scrollTo(0, 0) }
        }
        onIme?.invoke(imeUp)
        field.requestFocus()
        field.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun specialKeys(field: EditText) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        listOf("ü", "ö", "ß", "ä").forEachIndexed { i, ch ->
            addView(text(ch, 22.5f, BG, MEDIUM).apply {
                background = rounded(KEY, 12)
                setOnClickListener {
                    insertAtCursor(field, ch)
                    field.requestFocus()
                }
            }, lp(dp(48), dp(48)).apply { if (i > 0) marginStart = dp(10) })
        }
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

    /** Arrange (Figma board D): placed words are blue tiles in a dark box; the wand ends the bank's first row. */
    private fun arrange(q: Question) {
        prompt(q, 31)
        val chosen = mutableListOf<Int>()
        val answerBox = FlowLayout(this, dp(6)).apply {
            background = rounded(SURFACE, 25)
            setPadding(dp(17), dp(16), dp(17), dp(16))
            minimumHeight = dp(187)
        }
        val bank = FlowLayout(this, dp(7), firstRowInset = dp(45))
        fun tile(i: Int, placed: Boolean) = text(q.tiles[i], if (placed) 19f else 22.5f, BG, MEDIUM).apply {
            background = rounded(if (placed) TILE_BLUE else KEY, 12)
            minHeight = dp(if (placed) 28 else 35)
            setPadding(dp(12), 0, dp(12), 0)
        }
        // The bank keeps every tile in its original slot: a picked word leaves an empty hole
        // (INVISIBLE still occupies its space) so the remaining words never jump around.
        val bankTiles = q.tiles.indices.map { i -> tile(i, placed = false).also { bank.addView(it) } }
        fun render() {
            answerBox.removeAllViews()
            chosen.forEach { i ->
                answerBox.addView(tile(i, placed = true).apply { setOnClickListener { chosen.remove(i); render() } })
            }
            bankTiles.forEachIndexed { i, v ->
                v.visibility = if (i in chosen) View.INVISIBLE else View.VISIBLE
            }
        }
        bankTiles.forEachIndexed { i, v ->
            v.setOnClickListener { if (i !in chosen) { chosen.add(i); render() } }
        }
        render()
        val wand = icon(R.drawable.ic_wand, BG, 38).apply {
            background = oval(YELLOW)
            contentDescription = "Reveal next word (counts as a miss)"
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
        body.addView(gap(24))
        body.addView(answerBox, lp(MATCH, WRAP))
        body.addView(gap(28))
        body.addView(FrameLayout(this).apply {
            addView(bank, FrameLayout.LayoutParams(MATCH, WRAP))
            addView(wand, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END))
        }, lp(MATCH, WRAP))
        body.addView(gap(10))
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

    /** The answer screen (Figma boards D/E/F): what the card asked, the answer, and the wrong answer in red. */
    private fun showFeedback(q: Question, fb: AnswerFeedback, input: String) {
        sessionScreen()
        val ok = fb.correct
        playSfx(ok)
        if (q.taskTotal > 0u && fb.scored) {
            val done = (learnScored[q.word] ?: 0) + 1
            learnScored[q.word] = done
            if (done >= q.taskTotal.toInt()) learnDone.add(q.word)
        }
        val card = getCard(q.word)
        body.addView(topBlock(q, sessionProgress(), card?.audioFile, bar = false, onLearned = { learned(q, refresh = false) }))
        // what the card asked: its prompt, or the meaning when the prompt was the audio alone
        val asked = q.prompt.ifBlank { card?.meaning.orEmpty() }
        if (asked.isNotBlank() && asked != fb.expected) {
            body.addView(gap(26))
            body.addView(text(asked, 20f, KEY, LIGHT))
        }
        body.addView(gap(34))
        body.addView(text(fb.expected, 30f, TEXT, MEDIUM))
        if (!ok) {
            body.addView(gap(30))
            body.addView(text("your answer:", 15f, KEY, LIGHT))
            body.addView(text(yourAnswer(q, fb.expected, input), 25f, TEXT, LIGHT))
        }
        if (q.retry) {
            body.addView(gap(10))
            body.addView(text("Second try — not scored", 13f, MUTED))
        }
        body.addView(gap(40))
        body.addView(pill(if (fb.finished) "See results" else "Continue") {
            if (fb.finished) showEnd() else showQuestion()
        })
        afterRender()
        sayWordLater(q, ok)
    }

    /** Let the correct/wrong sound finish, then say the German word (not when the card itself was audio). */
    private fun sayWordLater(q: Question, ok: Boolean) {
        val audio = getCard(q.word)?.audioFile ?: return
        if (!autoplay || q.promptAudio != null) return
        val run = Runnable {
            pendingWordAudio = null
            playAudio(audio)
        }
        pendingWordAudio = run
        ui.postDelayed(run, if (ok) 1350L else 600L)
    }

    /** The wrong answer: per word for typed answers (right words stay plain), the whole option for a pick. */
    private fun yourAnswer(q: Question, expected: String, input: String): CharSequence {
        if (input.isBlank()) return marked("(no answer)", listOf(true, true), WRONG)
        val typed = q.kind == QKind.TYPING || q.kind == QKind.MEANING_TYPING || q.kind == QKind.HEARING ||
            q.kind == QKind.ARRANGE || q.kind == QKind.CLOZE_TYPING
        val flags = if (typed) markMistakes(expected, input).typed else words(input).map { true }
        return marked(input, flags, WRONG)
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

    /** The words of [s] joined by spaces, the flagged ones in [color]. */
    private fun marked(s: String, flags: List<Boolean>, color: Int): CharSequence {
        val ws = words(s)
        val out = SpannableStringBuilder()
        ws.forEachIndexed { i, w ->
            if (i > 0) out.append(' ')
            val from = out.length
            out.append(w)
            if (flags.size == ws.size && flags[i]) {
                out.setSpan(ForegroundColorSpan(color), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
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
        const val COURSE_NAME = "German A2" // the header's wording
    }
}
