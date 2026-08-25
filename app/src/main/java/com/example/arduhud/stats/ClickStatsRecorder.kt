package com.example.arduhud.stats

import android.os.SystemClock

sealed class ClickJournalEntry {
    abstract val tMs: Long

    data class Click(
        override val tMs: Long,
        val direction: String,
        val count: Int,
    ) : ClickJournalEntry()

    data class DirectionFlip(
        override val tMs: Long,
        val from: String,
        val to: String,
    ) : ClickJournalEntry()
}

data class DirectionIntervalStats(
    val direction: String,
    val clickCount: Int,
    val avgIntervalMs: Float?,
    val minIntervalMs: Long?,
    val maxIntervalMs: Long?,
)

data class ClickSessionSummary(
    val durationMs: Long,
    val totalClicks: Int,
    val flips: Int,
    val byDirection: List<DirectionIntervalStats>,
)

/**
 * Journal while lightning is armed; summary computed on disarm.
 * Times are ms since arm (elapsedRealtime).
 */
class ClickStatsRecorder {

    private var armed = false
    private var armElapsedMs = 0L
    private val entries = ArrayList<ClickJournalEntry>()
    private val clickTimesByDir = LinkedHashMap<String, ArrayList<Long>>()
    private var lastPulse: ClickPulse? = null
    private var lastSummary: ClickSessionSummary? = null

    val isArmed: Boolean get() = armed
    val journal: List<ClickJournalEntry> get() = entries.toList()
    val summary: ClickSessionSummary? get() = lastSummary

    fun arm() {
        armed = true
        armElapsedMs = SystemClock.elapsedRealtime()
        entries.clear()
        clickTimesByDir.clear()
        lastPulse = null
        lastSummary = null
    }

    fun onClick(pulse: ClickPulse) {
        if (!armed) return
        val tMs = SystemClock.elapsedRealtime() - armElapsedMs
        val prev = lastPulse
        if (prev != null && pulse.isOppositeOf(prev)) {
            entries += ClickJournalEntry.DirectionFlip(
                tMs = tMs,
                from = prev.directionKey,
                to = pulse.directionKey,
            )
        }

        val dir = pulse.directionKey
        val last = entries.lastOrNull()
        if (last is ClickJournalEntry.Click && last.direction == dir) {
            entries[entries.lastIndex] = last.copy(count = last.count + 1)
        } else {
            entries += ClickJournalEntry.Click(tMs = tMs, direction = dir, count = 1)
        }
        clickTimesByDir.getOrPut(dir) { ArrayList() }.add(tMs)
        lastPulse = pulse
    }

    fun disarm(): ClickSessionSummary? {
        if (!armed) return lastSummary
        armed = false
        val duration = SystemClock.elapsedRealtime() - armElapsedMs
        var total = 0
        var flips = 0
        for (e in entries) {
            when (e) {
                is ClickJournalEntry.Click -> total += e.count
                is ClickJournalEntry.DirectionFlip -> flips++
            }
        }
        val byDir = clickTimesByDir.map { (dir, times) ->
            val intervals = ArrayList<Long>()
            for (i in 1 until times.size) {
                intervals += times[i] - times[i - 1]
            }
            DirectionIntervalStats(
                direction = dir,
                clickCount = times.size,
                avgIntervalMs = if (intervals.isEmpty()) {
                    null
                } else {
                    intervals.average().toFloat()
                },
                minIntervalMs = intervals.minOrNull(),
                maxIntervalMs = intervals.maxOrNull(),
            )
        }
        lastSummary = ClickSessionSummary(
            durationMs = duration,
            totalClicks = total,
            flips = flips,
            byDirection = byDir,
        )
        return lastSummary
    }

    fun clear() {
        armed = false
        entries.clear()
        clickTimesByDir.clear()
        lastPulse = null
        lastSummary = null
    }
}
