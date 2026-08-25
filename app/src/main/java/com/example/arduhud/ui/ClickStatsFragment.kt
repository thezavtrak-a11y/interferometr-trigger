package com.example.arduhud.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.arduhud.AppViewModel
import com.example.arduhud.R
import com.example.arduhud.stats.ClickJournalEntry
import com.example.arduhud.stats.ClickSessionSummary
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.Locale

class ClickStatsFragment : Fragment() {

    private val viewModel: AppViewModel by activityViewModels()

    private lateinit var statsStatusText: TextView
    private lateinit var journalText: TextView
    private lateinit var summaryText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_click_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statsStatusText = view.findViewById(R.id.statsStatusText)
        journalText = view.findViewById(R.id.journalText)
        summaryText = view.findViewById(R.id.summaryText)
        view.findViewById<MaterialButton>(R.id.clearStatsButton).setOnClickListener {
            viewModel.clearClickStats()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.clickJournal.collect { entries ->
                        journalText.text = formatJournal(entries)
                    }
                }
                launch {
                    viewModel.clickSessionSummary.collect { summary ->
                        summaryText.text = formatSummary(summary)
                    }
                }
                launch {
                    viewModel.clickStatsArmed.collect { armed ->
                        statsStatusText.text = if (armed) {
                            getString(R.string.click_stats_status_recording)
                        } else {
                            getString(R.string.click_stats_status_idle)
                        }
                    }
                }
            }
        }
    }

    private fun formatJournal(entries: List<ClickJournalEntry>): String {
        if (entries.isEmpty()) return getString(R.string.click_stats_journal_empty)
        return entries.joinToString("\n") { e ->
            val t = formatSec(e.tMs)
            when (e) {
                is ClickJournalEntry.Click -> {
                    val count = if (e.count > 1) "×${e.count}" else "×1"
                    getString(R.string.click_stats_line_click, t, count, e.direction)
                }
                is ClickJournalEntry.DirectionFlip -> {
                    getString(R.string.click_stats_line_flip, t, e.from, e.to)
                }
            }
        }
    }

    private fun formatSummary(summary: ClickSessionSummary?): String {
        if (summary == null) return getString(R.string.click_stats_summary_empty)
        val sb = StringBuilder()
        sb.append(
            getString(
                R.string.click_stats_summary_head,
                summary.durationMs / 1000f,
                summary.totalClicks,
                summary.flips,
            ),
        )
        if (summary.byDirection.isEmpty()) {
            sb.append('\n').append(getString(R.string.click_stats_summary_no_dirs))
            return sb.toString()
        }
        for (dir in summary.byDirection) {
            sb.append('\n')
            val avg = dir.avgIntervalMs?.let { formatMs(it.toLong()) } ?: "—"
            val min = dir.minIntervalMs?.let { formatMs(it) } ?: "—"
            val max = dir.maxIntervalMs?.let { formatMs(it) } ?: "—"
            sb.append(
                getString(
                    R.string.click_stats_summary_dir,
                    dir.direction,
                    dir.clickCount,
                    avg,
                    min,
                    max,
                ),
            )
        }
        return sb.toString()
    }

    private fun formatSec(ms: Long): String =
        String.format(Locale.US, "%.2f", ms / 1000f)

    private fun formatMs(ms: Long): String {
        return if (ms >= 1000L) {
            String.format(Locale.US, "%.2f с", ms / 1000f)
        } else {
            "$ms мс"
        }
    }
}
