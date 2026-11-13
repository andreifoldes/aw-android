package net.activitywatch.android.automation

/**
 * A foreground app session starting or ending, as seen in (near) real time.
 * Timestamps are wall-clock milliseconds, the same basis as UsageEvents.
 */
sealed class SessionTransition {
    abstract val packageName: String
    /** When the transition happened. */
    abstract val at: Long

    data class Opened(override val packageName: String, override val at: Long) : SessionTransition()

    data class Closed(
        override val packageName: String,
        val startedAt: Long,
        val endedAt: Long,
    ) : SessionTransition() {
        override val at: Long get() = endedAt
        val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)
    }
}

/**
 * Turns the ordered stream of foreground events (activity resumed/paused, screen off)
 * into app-session open/close transitions, live.
 *
 * Follows the same single-foreground-app model as `parseForegroundSessions` (which builds
 * the stored history), with one addition needed for live use: an app's PAUSE is held for
 * [closeGraceMs] before it counts as the end of the session. Moving between two of an
 * app's own activities logs PAUSE(A) then RESUME(A) a few ms apart, and the two can land
 * in different polls; without the grace period that would fire a spurious close + open.
 *
 * The grace period only delays a close when *nothing* comes to the foreground afterwards.
 * The usual case (another app or the launcher resuming) closes the old session at once,
 * and the screen turning off closes it at once too.
 *
 * Pure logic: no Android APIs, so it is unit-tested on the JVM.
 */
class ForegroundTransitionTracker(private val closeGraceMs: Long = DEFAULT_CLOSE_GRACE_MS) {

    private class PendingClose(val packageName: String, val startedAt: Long, val pausedAt: Long)

    /** The app currently in the foreground, or null if none (or one that is pausing). */
    var foreground: String? = null
        private set
    private var foregroundStart = 0L
    private var pending: PendingClose? = null

    fun onResumed(packageName: String, timestamp: Long): List<SessionTransition> {
        val out = mutableListOf<SessionTransition>()
        pending?.let { p ->
            pending = null
            if (p.packageName == packageName && timestamp - p.pausedAt <= closeGraceMs) {
                // Same app came straight back (activity switch inside the app): one session.
                foreground = packageName
                foregroundStart = p.startedAt
                return out
            }
            out += SessionTransition.Closed(p.packageName, p.startedAt, p.pausedAt)
        }
        // Re-resumed without an intervening pause: still the same session.
        if (foreground == packageName) return out
        foreground?.let { out += SessionTransition.Closed(it, foregroundStart, timestamp) }
        foreground = packageName
        foregroundStart = timestamp
        out += SessionTransition.Opened(packageName, timestamp)
        return out
    }

    fun onPaused(packageName: String, timestamp: Long): List<SessionTransition> {
        // Stale or out-of-order pauses for apps that are not in front are ignored.
        if (foreground != packageName) return emptyList()
        pending = PendingClose(packageName, foregroundStart, timestamp)
        foreground = null
        return emptyList()
    }

    /** Screen off / shutdown: nothing is in the foreground any more, no grace period. */
    fun onScreenOff(timestamp: Long): List<SessionTransition> {
        val out = mutableListOf<SessionTransition>()
        pending?.let { out += SessionTransition.Closed(it.packageName, it.startedAt, it.pausedAt) }
        pending = null
        foreground?.let { out += SessionTransition.Closed(it, foregroundStart, maxOf(timestamp, foregroundStart)) }
        foreground = null
        return out
    }

    /** Commits a held close once its grace period has run out with nothing resuming. */
    fun flush(now: Long): List<SessionTransition> {
        val p = pending ?: return emptyList()
        if (now - p.pausedAt <= closeGraceMs) return emptyList()
        pending = null
        return listOf(SessionTransition.Closed(p.packageName, p.startedAt, p.pausedAt))
    }

    /** Forget all state without emitting anything (e.g. when automations are switched off). */
    fun reset() {
        foreground = null
        pending = null
    }

    companion object {
        const val DEFAULT_CLOSE_GRACE_MS = 1500L
    }
}
