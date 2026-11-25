package app.yehaw.browser.core

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import app.yehaw.browser.MainActivity
import kotlin.system.exitProcess

/**
 * Self-repair layer. It cannot make Android or a device never fail; it makes every failure it can
 * observe end in a restart or a fix, and it escalates when the same failure repeats so a bad
 * state can never trap the user in a crash loop. Nothing is sent anywhere and no user-owned data
 * (bookmarks, saved pages, downloads) is ever deleted by recovery.
 *
 *  1. Uncaught crash  -> record it, schedule an automatic relaunch, then let the process die.
 *  2. Crash loop      -> 3 crashes within 2 minutes = safe start (home page, no tab restore).
 *  3. UI freeze       -> watchdog restarts the app if the main thread is stuck for 20 seconds.
 *  4. Renderer death  -> the activity reloads the lost page (rate limited), see [allowRenderRescue].
 *  5. Corrupt store   -> [BrowserStoreRepair] moves the damaged DB aside and starts a fresh one.
 */
object Guardian {
    private const val PREFS = "guardian"
    private const val K_CRASHES = "crash_times"
    private const val K_LAST = "last_crash"
    private const val K_TOTAL = "crash_total"
    private const val K_REPAIRS = "repairs_total"
    private const val LOOP_WINDOW_MS = 120_000L
    private const val LOOP_LIMIT = 3
    private const val FREEZE_RESTART_MS = 20_000L

    @Volatile private var installed = false
    @Volatile private var resumed = false
    @Volatile private var lastBeat = SystemClock.uptimeMillis()
    private val renderRescues = ArrayDeque<Long>()

    /** Call once from Application.onCreate (runs in every process). */
    fun install(app: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(app, error)
                if (isMainProcess(app)) scheduleRestart(app, 1_500L)
            } catch (_: Throwable) {
                // Recovery must never throw while handling a crash.
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); exitProcess(10) }
        }
    }

    /** True when the app has been crashing repeatedly and must start in safe mode this time. */
    fun shouldStartSafe(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return crashTimes(context).count { now - it in 0..LOOP_WINDOW_MS } >= LOOP_LIMIT
    }

    /** Call after the app has run stably; clears the crash streak so normal start resumes. */
    fun markStable(context: Context) {
        prefs(context).edit().remove(K_CRASHES).apply()
    }

    fun totalCrashesRecovered(context: Context): Int = prefs(context).getInt(K_TOTAL, 0)
    fun totalRepairs(context: Context): Int = prefs(context).getInt(K_REPAIRS, 0)
    fun noteRepair(context: Context) {
        val p = prefs(context)
        p.edit().putInt(K_REPAIRS, p.getInt(K_REPAIRS, 0) + 1).apply()
    }

    /** Limits automatic renderer rescues to 3 per minute so a page that keeps dying cannot loop. */
    fun allowRenderRescue(): Boolean {
        val now = SystemClock.uptimeMillis()
        while (renderRescues.isNotEmpty() && now - renderRescues.first() > 60_000L) renderRescues.removeFirst()
        if (renderRescues.size >= 3) return false
        renderRescues.addLast(now)
        return true
    }

    // ---- freeze watchdog -------------------------------------------------------------------

    fun setForeground(foreground: Boolean) {
        resumed = foreground
        lastBeat = SystemClock.uptimeMillis()
    }

    fun startWatchdog(app: Application) {
        val main = Handler(Looper.getMainLooper())
        val thread = Thread({
            while (true) {
                try {
                    main.post { lastBeat = SystemClock.uptimeMillis() }
                    Thread.sleep(2_000L)
                    if (resumed && SystemClock.uptimeMillis() - lastBeat > FREEZE_RESTART_MS) {
                        record(app, IllegalStateException("ui_freeze"))
                        scheduleRestart(app, 800L)
                        Process.killProcess(Process.myPid())
                        exitProcess(11)
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // Keep watching no matter what.
                }
            }
        }, "yeehaw-watchdog")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    // ---- internals -------------------------------------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun crashTimes(context: Context): List<Long> =
        prefs(context).getString(K_CRASHES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private fun record(context: Context, error: Throwable) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val times = (crashTimes(context) + now).takeLast(6).joinToString(",")
        // Only the exception class and top frame are kept: messages can contain page URLs.
        val top = error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "?"
        p.edit().putString(K_CRASHES, times).putString(K_LAST, "${error.javaClass.name} @ $top")
            .putInt(K_TOTAL, p.getInt(K_TOTAL, 0) + 1).commit()
    }

    private fun isMainProcess(app: Application): Boolean {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName() == app.packageName
        return true
    }

    private fun scheduleRestart(context: Context, delayMs: Long) {
        // Relaunch is best effort: recent Android versions may refuse background activity starts, in
        // which case the next manual open is the recovery point and the crash-loop guard still applies.
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(context, 7071, intent, flags)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pi)
    }
}
