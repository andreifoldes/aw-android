package net.activitywatch.android.automation

import net.activitywatch.android.automation.SessionTransition.Closed
import net.activitywatch.android.automation.SessionTransition.Opened
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTransitionTrackerTest {

    private val tracker = ForegroundTransitionTracker(closeGraceMs = 1500)

    @Test
    fun switchingAppsClosesPreviousAndOpensNextImmediately() {
        assertEquals(listOf(Opened("a", 1000)), tracker.onResumed("a", 1000))
        assertEquals(emptyList<SessionTransition>(), tracker.onPaused("a", 5000))
        assertEquals(
            listOf(Closed("a", 1000, 5000), Opened("b", 5010)),
            tracker.onResumed("b", 5010),
        )
    }

    @Test
    fun resumeOfAnotherAppWithoutPauseStillClosesPrevious() {
        tracker.onResumed("a", 0)
        assertEquals(listOf(Closed("a", 0, 3000), Opened("b", 3000)), tracker.onResumed("b", 3000))
    }

    @Test
    fun activitySwitchInsideOneAppIsOneSession() {
        tracker.onResumed("a", 0)
        tracker.onPaused("a", 2000)
        // Same app resumes its next activity a few ms later: no close, no reopen.
        assertEquals(emptyList<SessionTransition>(), tracker.onResumed("a", 2005))
        assertEquals(emptyList<SessionTransition>(), tracker.flush(10_000))
        tracker.onPaused("a", 20_000)
        assertEquals(listOf(Closed("a", 0, 20_000)), tracker.onResumed("b", 20_001).take(1))
    }

    @Test
    fun pauseWithNothingResumingClosesAfterGracePeriod() {
        tracker.onResumed("a", 0)
        tracker.onPaused("a", 4000)
        assertEquals(emptyList<SessionTransition>(), tracker.flush(5000))
        assertEquals(listOf(Closed("a", 0, 4000)), tracker.flush(5600))
        assertEquals(emptyList<SessionTransition>(), tracker.flush(9000))
    }

    @Test
    fun sameAppResumingAfterGraceIsANewSession() {
        tracker.onResumed("a", 0)
        tracker.onPaused("a", 1000)
        assertEquals(listOf(Closed("a", 0, 1000), Opened("a", 9000)), tracker.onResumed("a", 9000))
    }

    @Test
    fun screenOffClosesWithoutGrace() {
        tracker.onResumed("a", 0)
        assertEquals(listOf(Closed("a", 0, 7000)), tracker.onScreenOff(7000))
        assertNull(tracker.foreground)
        assertEquals(emptyList<SessionTransition>(), tracker.onScreenOff(8000))
    }

    @Test
    fun screenOffCommitsPendingCloseAtPauseTime() {
        tracker.onResumed("a", 0)
        tracker.onPaused("a", 3000)
        assertEquals(listOf(Closed("a", 0, 3000)), tracker.onScreenOff(3200))
    }

    @Test
    fun stalePauseForBackgroundAppIsIgnored() {
        tracker.onResumed("a", 0)
        tracker.onResumed("b", 1000)
        assertEquals(emptyList<SessionTransition>(), tracker.onPaused("a", 1001))
        assertEquals("b", tracker.foreground)
    }

    @Test
    fun repeatedResumeOfForegroundAppIsIgnored() {
        tracker.onResumed("a", 0)
        assertEquals(emptyList<SessionTransition>(), tracker.onResumed("a", 500))
    }

    @Test
    fun resetForgetsStateWithoutEmitting() {
        tracker.onResumed("a", 0)
        tracker.reset()
        assertNull(tracker.foreground)
        assertEquals(listOf(Opened("b", 10)), tracker.onResumed("b", 10))
    }

    @Test
    fun closedDurationIsEndMinusStart() {
        assertEquals(4000L, Closed("a", 1000, 5000).durationMs)
    }
}
