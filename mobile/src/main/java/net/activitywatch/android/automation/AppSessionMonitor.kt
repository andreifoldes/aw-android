package net.activitywatch.android.automation

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "AppSessionMonitor"

/**
 * Watches which app is in the foreground, close to real time, and hands every session
 * open/close to the [AutomationDispatcher].
 *
 * Source of truth is UsageStatsManager (the same events the session history is built
 * from, and a permission the app already holds), read incrementally:
 *  - polled every [AutomationStore.getPollIntervalMs] while the screen is on;
 *  - polled immediately when [poke] is called. The accessibility service calls it on
 *    every window change, which brings latency down to a few hundred ms when it is on.
 *
 * Nothing runs while no rule is enabled or while the screen is off (screen-off itself
 * closes the open session), so the cost when unused is zero.
 *
 * All state is confined to one HandlerThread.
 */
class AppSessionMonitor(
    context: Context,
    private val store: AutomationStore,
    private val dispatcher: AutomationDispatcher,
) {
    private val appContext = context.applicationContext
    private val usageStats = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val thread = HandlerThread("AutomationMonitor").apply { start() }
    private val handler = Handler(thread.looper)

    private val tracker = ForegroundTransitionTracker()

    // Timestamp of the newest usage event processed, and the events already seen at
    // exactly that timestamp (queryEvents' lower bound is inclusive). -1 = not primed.
    private var cursor = -1L
    private val seenAtCursor = HashSet<String>()

    private var active = false
    private var screenOn = true
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            poll()
            if (polling) handler.postDelayed(this, store.getPollIntervalMs())
        }
    }

    private val pokeRunnable = Runnable { if (polling) poll() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val at = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.post { onScreenOff(at) }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> handler.post {
                    screenOn = true
                    updatePolling()
                }
            }
        }
    }

    // Must be a field: SharedPreferences only holds listeners weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AutomationStore.KEY_RULES || key == AutomationStore.KEY_POLL_INTERVAL) {
            handler.post { refresh() }
        }
    }

    fun start() {
        instance = this
        store.registerListener(prefsListener)
        ContextCompat.registerReceiver(
            appContext,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        handler.post {
            screenOn = power.isInteractive
            refresh()
        }
        Log.i(TAG, "Started")
    }

    fun stop() {
        if (instance === this) instance = null
        store.unregisterListener(prefsListener)
        try {
            appContext.unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        dispatcher.shutdown()
        Log.i(TAG, "Stopped")
    }

    /** Check now (and twice more shortly after, since usage events can land late). */
    fun poke() {
        handler.removeCallbacks(pokeRunnable)
        handler.post(pokeRunnable)
        handler.postDelayed(pokeRunnable, 300)
        handler.postDelayed(pokeRunnable, 1000)
    }

    /** Re-read rules and polling interval. */
    private fun refresh() {
        val wasActive = active
        active = store.hasEnabledRules()
        if (!active && wasActive) {
            // Forget the open session: re-enabling later must not fire a stale close.
            tracker.reset()
            cursor = -1L
            seenAtCursor.clear()
        }
        Log.i(TAG, "Automations ${if (active) "active" else "idle"}, poll every ${store.getPollIntervalMs()} ms")
        // Restart the loop so a new interval applies right away.
        handler.removeCallbacks(pollRunnable)
        polling = false
        updatePolling()
    }

    private fun updatePolling() {
        val shouldPoll = active && screenOn
        if (shouldPoll && !polling) {
            polling = true
            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
        } else if (!shouldPoll && polling) {
            polling = false
            handler.removeCallbacks(pollRunnable)
            handler.removeCallbacks(pokeRunnable)
        }
    }

    private fun onScreenOff(at: Long) {
        screenOn = false
        // Give the system a moment to log the final pause / screen-off events, read them,
        // then close whatever is still open: with the screen off nothing is in front.
        handler.postDelayed({
            if (!active) return@postDelayed
            poll()
            if (!screenOn) tracker.onScreenOff(at).forEach(dispatcher::dispatch)
            updatePolling()
        }, SCREEN_OFF_SETTLE_MS)
    }

    private fun poll() {
        if (!active) return
        val now = System.currentTimeMillis()
        if (cursor < 0) {
            prime(now)
            return
        }
        val transitions = mutableListOf<SessionTransition>()
        readEvents(cursor, now) { type, pkg, ts -> transitions += feed(type, pkg, ts) }
        transitions += tracker.flush(now)
        transitions.forEach {
            Log.d(TAG, "Transition: $it")
            dispatcher.dispatch(it)
        }
    }

    /**
     * Establish which app is in front right now from recent history, without firing
     * anything: rules react to changes from here on, not to what happened before.
     */
    private fun prime(now: Long) {
        val from = now - PRIME_LOOKBACK_MS
        tracker.reset()
        seenAtCursor.clear()
        cursor = from
        readEvents(from, now) { type, pkg, ts -> feed(type, pkg, ts) }
        tracker.flush(now)
        Log.i(TAG, "Primed; foreground app: ${tracker.foreground ?: "none"}")
    }

    private inline fun readEvents(from: Long, to: Long, onEvent: (Int, String, Long) -> Unit) {
        val events = try {
            usageStats.queryEvents(from, to)
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents failed", e)
            return
        } ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            if (ts < cursor) continue
            val key = "${event.eventType}|${event.packageName}|${event.className}"
            if (ts == cursor && !seenAtCursor.add(key)) continue
            if (ts > cursor) {
                cursor = ts
                seenAtCursor.clear()
                seenAtCursor.add(key)
            }
            onEvent(event.eventType, event.packageName ?: "", ts)
        }
    }

    private fun feed(type: Int, pkg: String, ts: Long): List<SessionTransition> = when (type) {
        // ACTIVITY_RESUMED / ACTIVITY_PAUSED share their values with the pre-Q
        // MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND, as in SessionParser.
        UsageEvents.Event.ACTIVITY_RESUMED -> if (pkg.isEmpty()) emptyList() else tracker.onResumed(pkg, ts)
        UsageEvents.Event.ACTIVITY_PAUSED -> tracker.onPaused(pkg, ts)
        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
        UsageEvents.Event.DEVICE_SHUTDOWN -> tracker.onScreenOff(ts)
        else -> emptyList()
    }

    companion object {
        private const val PRIME_LOOKBACK_MS = 30 * 60 * 1000L
        private const val SCREEN_OFF_SETTLE_MS = 750L

        @Volatile
        private var instance: AppSessionMonitor? = null

        /** Ask the running monitor (if any) to check for a foreground change right now. */
        fun poke() {
            instance?.poke()
        }
    }
}
