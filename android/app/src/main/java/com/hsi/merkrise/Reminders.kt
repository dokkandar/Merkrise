package com.hsi.merkrise

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.hsi.merkrise.core.importState
import com.hsi.merkrise.core.loadSeed
import com.hsi.merkrise.core.nextReviewAlarm
import com.hsi.merkrise.core.stats
import com.hsi.merkrise.core.wordCount
import java.io.File
import java.util.TimeZone

/**
 * "N words ready to review" reminders. The core decides WHEN (>= 5 words due,
 * quiet hours 22:00-08:00, 4 h gap); this file only turns that core-clock time
 * into one AlarmManager alarm and posts the notification.
 *
 * The core clock is epoch + the local time-zone offset, so an alarm time must be
 * converted back to real epoch at the AlarmManager boundary.
 */
object Reminders {
    const val EXTRA_START_DUE = "start_due"
    private const val CHANNEL = "reminders"
    private const val NOTIFICATION_ID = 1
    private const val MIN_WORDS = 5u

    private fun prefs(c: Context) = c.getSharedPreferences("merkrise", Context.MODE_PRIVATE)
    private fun tzOffsetSecs(realMs: Long) = TimeZone.getDefault().getOffset(realMs) / 1000L

    /** Unix seconds shifted to local time: the clock the core uses for due dates. */
    fun coreNow(): Long {
        val ms = System.currentTimeMillis()
        return ms / 1000 + tzOffsetSecs(ms)
    }

    /**
     * The alarm/boot receivers can run in a process that never started the Activity:
     * load the course and the saved progress then. Never reloads a running core
     * (that would drop the Activity's session).
     */
    @Synchronized
    fun ensureCore(c: Context): Boolean {
        if (wordCount() > 0u) return true
        return try {
            val json = c.assets.open("seed.json").bufferedReader().use { it.readText() }
            loadSeed(json)
            val state = File(c.filesDir, "progress.json")
            if (state.exists()) importState(state.readText())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun alarmIntent(c: Context) = PendingIntent.getBroadcast(
        c, 0, Intent(c, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Set (or cancel) the one pending alarm from the core's answer. `studying` = the
     * user is answering cards right now: the 4 h gap then runs from this moment, so a
     * reminder never pops up in the middle of a session.
     */
    fun rearm(c: Context, studying: Boolean) {
        if (!ensureCore(c)) return
        val now = coreNow()
        val p = prefs(c)
        if (studying) p.edit().putLong("last_study", now).apply()
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(c)
        if (!canNotify(c)) {
            // nothing could be shown, so do not wake the phone for it; the next answer, app start
            // or granted permission arms the alarm again
            am.cancel(pi)
            return
        }
        val gate = maxOf(p.getLong("last_notified", 0L), p.getLong("last_study", 0L))
        val alarm = nextReviewAlarm(MIN_WORDS, now, gate)
        if (alarm.at < 0) {
            am.cancel(pi)
            return
        }
        val realMs = (alarm.at - tzOffsetSecs(System.currentTimeMillis())) * 1000
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, realMs, pi) // replaces the previous alarm
    }

    /** False when the permission is missing, notifications are off for the app, or our channel is off. */
    fun canNotify(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        return nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** The alarm went off: post the reminder if enough words are still due, then re-arm. */
    fun fire(c: Context) {
        if (!ensureCore(c)) return
        val now = coreNow()
        val due = stats(now).dueToday
        if (due >= MIN_WORDS) {
            // counts as a notification even when it cannot be shown, so a denied
            // permission cannot make the alarm fire in a loop
            prefs(c).edit().putLong("last_notified", now).apply()
            if (canNotify(c)) post(c, due)
        }
        rearm(c, studying = false)
    }

    fun post(c: Context, due: UInt) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Review reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            c, 1,
            Intent(c, MainActivity::class.java)
                .putExtra(EXTRA_START_DUE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val count = if (due > 20u) "20+" else due.toString()
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Merkrise")
                .setContentText("$count words ready to review")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

/** Runs headless (no Activity) when the alarm fires. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { Reminders.fire(context.applicationContext) } finally { pending.finish() }
        }.start()
    }
}

/**
 * After a reboot Android has dropped our alarm. After an app update the alarm survives, but it was
 * computed with the previous build's rules: compute it again with this build's.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try { Reminders.rearm(context.applicationContext, studying = false) } finally { pending.finish() }
        }.start()
    }
}
