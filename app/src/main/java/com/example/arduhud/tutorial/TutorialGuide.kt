package com.example.arduhud.tutorial

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.example.arduhud.AppViewModel
import com.example.arduhud.MainActivity
import com.example.arduhud.R
import com.example.arduhud.sensors.SensorChannel
import com.example.arduhud.ui.ConnectionFragment

class TutorialGuide(
    private val activity: MainActivity,
    private val overlay: TutorialOverlayView,
    private val viewModel: AppViewModel,
) {
    private var index = 0
    private var running = false
    private var savedMinMotion = 0f
    private var savedMinRest = 0f
    private var savedThreshold = 0f
    private var savedSmooth = 0f
    private var savedSum = true
    private var savedChannels: Set<SensorChannel> = emptySet()
    private val applyRunnable = Runnable { bindCurrentStep() }
    private var animStartedFor = -1

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (index > 0) goTo(index - 1) else finish()
        }
    }

    val isRunning: Boolean get() = running

    init {
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        overlay.backButton.setOnClickListener {
            if (index > 0) goTo(index - 1)
        }
        overlay.nextButton.setOnClickListener {
            if (index < TutorialCatalog.steps.lastIndex) goTo(index + 1)
        }
        overlay.finishButton.setOnClickListener { finish() }
        overlay.onHoleTouch = {
            activity.findMainFragment()?.cancelTutorialAutoplay()
        }
    }

    fun start() {
        if (running) return
        running = true
        index = 0
        overlay.isVisible = true
        overlay.bringToFront()
        backCallback.isEnabled = true
        savedMinMotion = viewModel.getMinMotionSec()
        savedMinRest = viewModel.getMinRestSec()
        savedThreshold = viewModel.getThreshold()
        savedSmooth = viewModel.getWaveSmoothSec()
        savedSum = viewModel.getUseSum()
        savedChannels = viewModel.getEnabledChannels().toSet()
        viewModel.setMinMotionSec(DEMO_MIN_MOTION_SEC)
        viewModel.setMinRestSec(DEMO_MIN_REST_SEC)
        viewModel.setTutorialDemo(true)
        activity.findMainFragment()?.prepareTutorialWaveform()
        goTo(0)
    }

    fun finish() {
        if (!running) return
        running = false
        overlay.removeCallbacks(applyRunnable)
        overlay.isVisible = false
        overlay.highlight(null)
        backCallback.isEnabled = false
        activity.findMainFragment()?.stopTutorialAnimations()
        viewModel.setTutorialFlashEnabled(false)
        viewModel.setTutorialDemo(false)
        viewModel.setMinMotionSec(savedMinMotion)
        viewModel.setMinRestSec(savedMinRest)
        viewModel.setThreshold(savedThreshold)
        viewModel.setWaveSmoothSec(savedSmooth)
        viewModel.setUseSum(savedSum)
        viewModel.setEnabledChannels(savedChannels)
        activity.findMainFragment()?.let { main ->
            main.applyTutorialChrome(TutorialChrome.None)
            main.syncChannelUiFromViewModel()
            main.prepareTutorialWaveform()
        }
        activity.openSensorScreen()
        TutorialPrefs.markSeen(activity)
    }

    private fun goTo(newIndex: Int) {
        index = newIndex.coerceIn(0, TutorialCatalog.steps.lastIndex)
        val step = TutorialCatalog.steps[index]
        activity.findMainFragment()?.cancelTutorialAutoplay()
        if (step.targetId != R.id.clickArmButton) {
            viewModel.setTutorialFlashEnabled(false)
        }
        when (step.page) {
            TutorialPage.Main -> activity.openSensorScreen(step.animatePage)
            TutorialPage.Stats -> activity.openClickStatsScreen(step.animatePage)
            TutorialPage.Settings -> {
                activity.openSettingsScreen(step.animatePage)
                activity.findConnectionFragment()?.prepareTutorialStep(step.targetId)
            }
        }
        activity.findMainFragment()?.applyTutorialChrome(step.chrome)
        overlay.removeCallbacks(applyRunnable)
        animStartedFor = -1
        val delay = when {
            step.animatePage -> 420L
            step.chrome == TutorialChrome.Channels ||
                step.chrome == TutorialChrome.Timing ||
                step.chrome == TutorialChrome.Touchpad -> 300L
            else -> 80L
        }
        overlay.postDelayed(applyRunnable, delay)
        overlay.postDelayed(applyRunnable, delay + 180L)
    }

    private fun bindCurrentStep() {
        if (!running) return
        val step = TutorialCatalog.steps[index]
        overlay.cardSlot = step.cardSlot
        overlay.bindCopy(
            index,
            TutorialCatalog.steps.size,
            activity.getString(step.titleRes),
            activity.getString(step.bodyRes),
        )
        val target = if (step.targetId == 0) {
            null
        } else {
            activity.findViewById<View>(step.targetId)
        }
        val ready = target?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
        val grid = step.hole == TutorialHole.WaveformGrid
        val anchor = if (grid) activity.findViewById<View>(R.id.activityValueText) else null
        val extraPass = when {
            step.chrome == TutorialChrome.Touchpad ->
                activity.findViewById<View>(R.id.touchpadSurface)
            step.cardSlot == TutorialCardSlot.BelowHole ->
                activity.findViewById<View>(R.id.motionWaveform)
            else -> null
        }
        overlay.highlight(ready, grid, anchor, extraPass)
        viewModel.setTutorialFlashEnabled(step.targetId == R.id.clickArmButton)
        if (animStartedFor != index) {
            animStartedFor = index
            activity.findMainFragment()?.playTutorialAnim(step.anim)
        }
    }

    companion object {
        const val DEMO_MIN_MOTION_SEC = 0.3f
        const val DEMO_MIN_REST_SEC = 0.5f
    }
}

fun MainActivity.findConnectionFragment(): ConnectionFragment? {
    return supportFragmentManager.fragments
        .filterIsInstance<ConnectionFragment>()
        .firstOrNull()
}
