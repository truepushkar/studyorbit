package com.studyorbit.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

internal data class StudyUiState(
    val active: Session?, val history: List<StudyLog>, val today: LocalDate,
    val focusMinutes: Int, val breakMinutes: Int, val goal: Int, val subject: String,
    val mode: TimerMode, val strict: Boolean, val timerBackground: TimerBackground, val sound: Soundscape,
    val backgroundColor: Int?, val timerPhotoRevision: Long,
    val nudges: Boolean, val result: Int, val resultTime: Long, val resultKind: FocusResult
)

internal class StudyViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val store = FocusStore(application)
    private val context = application
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    private fun snapshot() = StudyUiState(
        store.session(), store.history(), LocalDate.now(), store.focusMinutes, store.breakMinutes,
        store.goalMinutes, store.subject,
        TimerMode.entries.getOrElse(store.prefs.getInt("timer_mode", 0)) { TimerMode.TIMER },
        store.prefs.getBoolean("strict_preference", false),
        store.timerBackground,
        store.soundscape(), store.backgroundColor, store.timerPhotoRevision, store.prefs.getBoolean("focus_nudges", true),
        saved["result"] ?: -1, saved["resultTime"] ?: 0L, saved["resultKind"] ?: FocusResult.FOCUS
    )

    private fun setResult(minutes: Int, time: Long, kind: FocusResult) {
        saved["result"] = minutes
        saved["resultTime"] = time
        saved["resultKind"] = kind
    }

    fun reset() {
        saved["result"] = -1
        refresh()
    }

    fun finish() {
        val previous = store.session() ?: return
        val elapsed = previous.displayTime()
        store.stop()
        if (previous.mode == TimerMode.STOPWATCH) {
            setResult((elapsed / MINUTE).toInt(), elapsed, FocusResult.STOPWATCH)
        } else {
            setResult(0, elapsed, FocusResult.ENDED)
        }
        mutableState.value = snapshot()
    }

    private fun refresh() {
        val before = mutableState.value
        val after = snapshot()
        val previous = before.active
        if (previous != null && after.active == null) {
            val log = after.history.lastOrNull()?.takeIf { it != before.history.lastOrNull() }
            if (previous.phase == Phase.BREAK && previous.displayTime() == 0L) {
                if (previous.mode == TimerMode.POMODORO) {
                    val minutes = after.history.takeLast(previous.rounds).sumOf { it.minutes }
                    setResult(minutes, minutes * MINUTE, FocusResult.CYCLE)
                } else setResult(0, previous.duration, FocusResult.BREAK)
            } else if (log != null) {
                val kind = if (previous.mode == TimerMode.STOPWATCH) FocusResult.STOPWATCH else FocusResult.FOCUS
                setResult(log.minutes, log.minutes * MINUTE, kind)
            }
        }
        mutableState.value = after.copy(result = saved["result"] ?: -1,
            resultTime = saved["resultTime"] ?: 0L, resultKind = saved["resultKind"] ?: FocusResult.FOCUS)
    }

    suspend fun observe() {
        val updates = Channel<Unit>(Channel.CONFLATED)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updates.trySend(Unit)
        }
        val clockChanges = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updates.trySend(Unit)
            }
        }
        val clockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        ContextCompat.registerReceiver(context, clockChanges, clockFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            store.restoreAlarm()
            StudyPlanner(context, store.prefs).arm()
            refresh()
            store.syncStudyNotification()
            while (true) {
                val midnight = (nextMidnight() - System.currentTimeMillis()).coerceAtLeast(1)
                val countdown = state.value.active?.takeIf { it.running && it.mode != TimerMode.STOPWATCH }
                val nextRefresh = minOf(midnight, countdown?.displayTime()?.coerceAtLeast(1) ?: Long.MAX_VALUE)
                withTimeoutOrNull(nextRefresh) { updates.receive() }
                refresh()
            }
        } finally {
            context.unregisterReceiver(clockChanges)
            store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
            updates.close()
        }
    }
}
