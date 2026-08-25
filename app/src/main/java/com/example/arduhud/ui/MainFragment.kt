package com.example.arduhud.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.arduhud.AppViewModel
import com.example.arduhud.MainActivity
import com.example.arduhud.R
import com.example.arduhud.link.EspLinkState
import com.example.arduhud.sensors.SensorChannel
import com.example.arduhud.sensors.SensorProcessor
import com.example.arduhud.sensors.TimeWindowSec
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class MainFragment : Fragment() {

    private val viewModel: AppViewModel by activityViewModels()

    private lateinit var waveformView: MotionWaveformView
    private lateinit var activityValueText: TextView
    private lateinit var motionStateText: TextView
    private lateinit var toolbarStatusText: TextView
    private lateinit var usbStatusDot: View
    private lateinit var clickFlashOverlay: View
    private lateinit var channelsPanel: View
    private lateinit var timingPanel: View
    private lateinit var accelChipGroup: ChipGroup
    private lateinit var gyroChipGroup: ChipGroup
    private lateinit var sumChip: Chip
    private lateinit var touchpadContainer: ViewGroup
    private lateinit var pauseButton: ImageButton
    private lateinit var clickArmButton: ImageButton
    private lateinit var channelsButton: ImageButton
    private lateinit var timingButton: ImageButton
    private lateinit var mouseButton: ImageButton
    private lateinit var orientationButton: ImageButton
    private lateinit var statsButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var motionGateSeekBar: TdSliderView
    private lateinit var restGateSeekBar: TdSliderView
    private lateinit var waveSmoothSeekBar: TdSliderView
    private lateinit var motionMinusButton: MaterialButton
    private lateinit var motionPlusButton: MaterialButton
    private lateinit var restMinusButton: MaterialButton
    private lateinit var restPlusButton: MaterialButton
    private lateinit var smoothMinusButton: MaterialButton
    private lateinit var smoothPlusButton: MaterialButton
    private lateinit var motionScaleButtons: List<MaterialButton>
    private lateinit var restScaleButtons: List<MaterialButton>

    private var updatingChannelChips = false
    private var waveformPaused = false
    private var wasConnected = false
    private var statusBlinkAnimator: ObjectAnimator? = null
    private var lightningBlinkAnimator: ObjectAnimator? = null
    private var pathReplaying = false
    private var motionScaleMaxSec = MOTION_SCALES.last()
    private var restScaleMaxSec = REST_SCALES.last()

    private val accelOffChipId = View.generateViewId()
    private val gyroOffChipId = View.generateViewId()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeTouchpad()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        waveformView = view.findViewById(R.id.motionWaveform)
        activityValueText = view.findViewById(R.id.activityValueText)
        motionStateText = view.findViewById(R.id.motionStateText)
        toolbarStatusText = view.findViewById(R.id.toolbarStatusText)
        usbStatusDot = view.findViewById(R.id.bleStatusDot)
        clickFlashOverlay = view.findViewById(R.id.clickFlashOverlay)
        channelsPanel = view.findViewById(R.id.channelsPanel)
        timingPanel = view.findViewById(R.id.timingPanel)
        accelChipGroup = view.findViewById(R.id.accelChipGroup)
        gyroChipGroup = view.findViewById(R.id.gyroChipGroup)
        sumChip = view.findViewById(R.id.sumChip)
        touchpadContainer = view.findViewById(R.id.touchpadContainer)
        pauseButton = view.findViewById(R.id.pauseButton)
        clickArmButton = view.findViewById(R.id.clickArmButton)
        channelsButton = view.findViewById(R.id.channelsButton)
        timingButton = view.findViewById(R.id.timingButton)
        mouseButton = view.findViewById(R.id.mouseButton)
        orientationButton = view.findViewById(R.id.orientationButton)
        statsButton = view.findViewById(R.id.statsButton)
        settingsButton = view.findViewById(R.id.settingsButton)
        motionGateSeekBar = view.findViewById(R.id.motionGateSeekBar)
        restGateSeekBar = view.findViewById(R.id.restGateSeekBar)
        waveSmoothSeekBar = view.findViewById(R.id.waveSmoothSeekBar)
        motionMinusButton = view.findViewById(R.id.motionMinusButton)
        motionPlusButton = view.findViewById(R.id.motionPlusButton)
        restMinusButton = view.findViewById(R.id.restMinusButton)
        restPlusButton = view.findViewById(R.id.restPlusButton)
        smoothMinusButton = view.findViewById(R.id.smoothMinusButton)
        smoothPlusButton = view.findViewById(R.id.smoothPlusButton)
        motionScaleButtons = listOf(
            view.findViewById(R.id.motionScale01Button),
            view.findViewById(R.id.motionScale05Button),
            view.findViewById(R.id.motionScale5Button),
        )
        restScaleButtons = listOf(
            view.findViewById(R.id.restScale05Button),
            view.findViewById(R.id.restScale2Button),
            view.findViewById(R.id.restScale10Button),
        )

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        view.findViewById<View>(R.id.wifiConnectButton).setOnClickListener {
            stopStatusBlink()
            viewModel.connectWifi()
        }
        mouseButton.setOnClickListener { showTouchpad() }
        channelsButton.setOnClickListener { toggleChannelsPanel() }
        timingButton.setOnClickListener { toggleTimingPanel() }
        orientationButton.setOnClickListener { toggleOrientation() }
        statsButton.setOnClickListener {
            (activity as? MainActivity)?.openClickStatsScreen()
        }
        settingsButton.setOnClickListener {
            (activity as? MainActivity)?.openSettingsScreen()
        }
        pauseButton.setOnClickListener {
            setWaveformPaused(!waveformPaused)
        }
        clickArmButton.setOnClickListener {
            viewModel.toggleClickOutputEnabled()
            refreshToolbarHighlights()
        }
        // Don't set OnTouchListener on waveform — it can steal the stream from
        // ScaleGestureDetector (pinch Y/time). Clear blink via dedicated callback.
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                stopStatusBlink()
            }
            false
        }
        waveformView.setOnTouchDownListener { stopStatusBlink() }

        setupWaveform()
        setupChannelsPanel()
        setupDetectGates()
        refreshTimingOverlay()
        applyOrientationLayout()
        refreshToolbarHighlights()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.motionData.collect { data ->
                        waveformView.addSample(
                            timestampNs = data.timestampNs,
                            values = data.channelValues,
                            activity = data.activity,
                            clickMarkerNs = data.clickMarkerNs,
                        )
                        val activityText = getString(
                            R.string.activity_value_channels,
                            data.activity,
                            formatChannels(data.enabledChannels),
                            if (data.useSum) {
                                getString(R.string.detect_mode_sum)
                            } else {
                                getString(R.string.detect_mode_max)
                            },
                        )
                        val stateText = if (data.isMoving) {
                            getString(R.string.state_moving)
                        } else {
                            getString(R.string.state_rest)
                        }
                        activityValueText.text = activityText
                        motionStateText.text = stateText
                        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                            toolbarStatusText.text = "$activityText\n$stateText"
                        }
                    }
                }
                launch {
                    viewModel.connectionState.collect { state ->
                        updateUsbStatus(state)
                    }
                }
                launch {
                    viewModel.clickEvents.collect {
                        if (viewModel.isClickOutputEnabled()) {
                            flashClick()
                        }
                    }
                }
                launch {
                    viewModel.pathReplaying.collect { replaying ->
                        pathReplaying = replaying
                        if (replaying) startLightningBlink() else stopLightningBlink()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onUiForeground(true)
    }

    override fun onPause() {
        // Keep sensors + SoftAP while linked (FGS). Only UI-visibility flag changes.
        viewModel.onUiForeground(false)
        super.onPause()
    }

    private fun setupWaveform() {
        waveformView.setThreshold(viewModel.getThreshold())
        waveformView.setEnabledChannels(viewModel.getEnabledChannels())
        waveformView.setShowSum(viewModel.getUseSum())
        waveformView.setSmoothTauSec(viewModel.getWaveSmoothSec())
        waveformView.setTimeWindow(TimeWindowSec.S20)
        waveformView.setAutoscale(true)
        waveformView.setOnThresholdChangeListener { value ->
            viewModel.setThreshold(value)
        }
    }

    private fun setupChannelsPanel() {
        val enabled = viewModel.getEnabledChannels()
        fillAxisRow(
            group = accelChipGroup,
            options = SensorChannel.accelOptions,
            selected = enabled.filter { it.isAccel }.toSet(),
            offId = accelOffChipId,
        )
        fillAxisRow(
            group = gyroChipGroup,
            options = SensorChannel.gyroOptions,
            selected = enabled.filter { !it.isAccel }.toSet(),
            offId = gyroOffChipId,
        )

        wireAxisRow(accelChipGroup, accelOffChipId)
        wireAxisRow(gyroChipGroup, gyroOffChipId)

        sumChip.shapeAppearanceModel = sumChip.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(0f)
            .build()
        sumChip.chipCornerRadius = 0f
        sumChip.isCheckedIconVisible = false
        val sumAccent = ContextCompat.getColor(requireContext(), R.color.metro_accent)
        sumChip.isChecked = viewModel.getUseSum()
        applyMetroChipColors(sumChip, sumAccent, sumChip.isChecked)
        sumChip.setOnCheckedChangeListener { _, isChecked ->
            applyMetroChipColors(sumChip, sumAccent, isChecked)
            viewModel.setUseSum(isChecked)
            waveformView.setShowSum(isChecked)
        }

        setupWaveSmoothSlider()
    }

    private fun setupWaveSmoothSlider() {
        waveSmoothSeekBar.max = SMOOTH_SLIDER_STEPS
        waveSmoothSeekBar.fillColor = Color.parseColor("#00ADEF")
        waveSmoothSeekBar.progress = smoothSecToProgress(viewModel.getWaveSmoothSec())
        refreshWaveSmoothLabel(viewModel.getWaveSmoothSec())
        waveSmoothSeekBar.setOnProgressChangeListener { progress, _ ->
            val sec = smoothProgressToSec(progress)
            viewModel.setWaveSmoothSec(sec)
            waveformView.setSmoothTauSec(sec)
            refreshWaveSmoothLabel(sec)
        }
        smoothMinusButton.setOnClickListener {
            val next = (viewModel.getWaveSmoothSec() - SMOOTH_STEP_SEC)
                .coerceIn(0f, AppViewModel.WAVE_SMOOTH_MAX_SEC)
            applyWaveSmoothSec(next)
        }
        smoothPlusButton.setOnClickListener {
            val next = (viewModel.getWaveSmoothSec() + SMOOTH_STEP_SEC)
                .coerceIn(0f, AppViewModel.WAVE_SMOOTH_MAX_SEC)
            applyWaveSmoothSec(next)
        }
    }

    private fun applyWaveSmoothSec(sec: Float) {
        viewModel.setWaveSmoothSec(sec)
        waveformView.setSmoothTauSec(sec)
        waveSmoothSeekBar.progress = smoothSecToProgress(sec)
        refreshWaveSmoothLabel(sec)
    }

    private fun smoothProgressToSec(progress: Int): Float {
        if (waveSmoothSeekBar.max <= 0) return 0f
        return progress * AppViewModel.WAVE_SMOOTH_MAX_SEC / waveSmoothSeekBar.max
    }

    private fun smoothSecToProgress(sec: Float): Int {
        return ((sec / AppViewModel.WAVE_SMOOTH_MAX_SEC) * waveSmoothSeekBar.max)
            .toInt()
            .coerceIn(0, waveSmoothSeekBar.max)
    }

    private fun refreshWaveSmoothLabel(sec: Float) {
        waveSmoothSeekBar.labelText = if (sec <= 0.0005f) {
            getString(R.string.wave_smooth_label_off)
        } else {
            getString(R.string.wave_smooth_label, (sec * 1000f).toInt())
        }
    }

    private fun fillAxisRow(
        group: ChipGroup,
        options: List<SensorChannel>,
        selected: Set<SensorChannel>,
        offId: Int,
    ) {
        group.removeAllViews()
        for (channel in options) {
            val color = Color.parseColor(channel.colorHex)
            group.addView(
                Chip(requireContext()).apply {
                    id = View.generateViewId()
                    tag = channel
                    text = channel.shortLabel
                    isCheckable = true
                    isCheckedIconVisible = false
                    isChecked = channel in selected
                    // Metro: sharp tile, accent fill when checked.
                    shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                        .setAllCornerSizes(0f)
                        .build()
                    chipCornerRadius = 0f
                    applyMetroChipColors(this, color, channel in selected)
                },
            )
        }
        group.addView(
            Chip(requireContext()).apply {
                id = offId
                tag = TAG_OFF
                text = getString(R.string.channel_off)
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = selected.isEmpty()
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(0f)
                    .build()
                chipCornerRadius = 0f
                val muted = ContextCompat.getColor(requireContext(), R.color.metro_text_secondary)
                applyMetroChipColors(this, muted, selected.isEmpty())
            },
        )
    }

    /** WP10 tile chip: accent fill when on, chrome + accent edge when off. */
    private fun applyMetroChipColors(chip: Chip, accent: Int, checked: Boolean) {
        val chrome = ContextCompat.getColor(requireContext(), R.color.metro_chrome_raised)
        if (checked) {
            chip.chipBackgroundColor = ColorStateList.valueOf(accent)
            chip.setTextColor(Color.WHITE)
            chip.chipStrokeWidth = 0f
        } else {
            chip.chipBackgroundColor = ColorStateList.valueOf(chrome)
            chip.setTextColor(accent)
            chip.chipStrokeColor = ColorStateList.valueOf(accent)
            chip.chipStrokeWidth = resources.displayMetrics.density
        }
    }

    private fun refreshAxisChipColors(group: ChipGroup) {
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            when (val tag = chip.tag) {
                is SensorChannel ->
                    applyMetroChipColors(chip, Color.parseColor(tag.colorHex), chip.isChecked)
                TAG_OFF -> {
                    val muted = ContextCompat.getColor(requireContext(), R.color.metro_text_secondary)
                    applyMetroChipColors(chip, muted, chip.isChecked)
                }
            }
        }
    }

    private fun wireAxisRow(group: ChipGroup, offId: Int) {
        val offChip = group.findViewById<Chip>(offId) ?: return
        val channelChips = (0 until group.childCount)
            .mapNotNull { group.getChildAt(it) as? Chip }
            .filter { it.id != offId }

        for (chip in channelChips) {
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (updatingChannelChips) return@setOnCheckedChangeListener
                updatingChannelChips = true
                if (isChecked) {
                    offChip.isChecked = false
                } else if (channelChips.none { it.isChecked }) {
                    offChip.isChecked = true
                }
                updatingChannelChips = false
                refreshAxisChipColors(group)
                applyChannelSelection()
            }
        }

        offChip.setOnCheckedChangeListener { _, isChecked ->
            if (updatingChannelChips) return@setOnCheckedChangeListener
            updatingChannelChips = true
            if (isChecked) {
                channelChips.forEach { it.isChecked = false }
            } else if (channelChips.none { it.isChecked }) {
                offChip.isChecked = true
            }
            updatingChannelChips = false
            refreshAxisChipColors(group)
            applyChannelSelection()
        }
    }

    private fun applyChannelSelection() {
        val channels = linkedSetOf<SensorChannel>()
        channels += checkedChannels(accelChipGroup)
        channels += checkedChannels(gyroChipGroup)
        viewModel.setEnabledChannels(channels)
        waveformView.setEnabledChannels(channels)
    }

    private fun checkedChannels(group: ChipGroup): List<SensorChannel> {
        return (0 until group.childCount)
            .mapNotNull { group.getChildAt(it) as? Chip }
            .filter { it.isChecked && it.tag is SensorChannel }
            .map { it.tag as SensorChannel }
    }

    private fun setupDetectGates() {
        motionGateSeekBar.max = GATE_SLIDER_STEPS
        restGateSeekBar.max = GATE_SLIDER_STEPS
        motionGateSeekBar.fillColor = Color.parseColor("#FF9800")
        restGateSeekBar.fillColor = Color.parseColor("#66BB6A")

        motionScaleButtons.forEachIndexed { index, button ->
            button.setOnClickListener { setMotionScale(MOTION_SCALES[index]) }
        }
        restScaleButtons.forEachIndexed { index, button ->
            button.setOnClickListener { setRestScale(REST_SCALES[index]) }
        }

        setMotionScale(pickScale(viewModel.getMinMotionSec(), MOTION_SCALES), keepValue = true)
        setRestScale(pickScale(viewModel.getMinRestSec(), REST_SCALES), keepValue = true)

        motionMinusButton.setOnClickListener { nudgeMotion(-motionStepSec()) }
        motionPlusButton.setOnClickListener { nudgeMotion(motionStepSec()) }
        restMinusButton.setOnClickListener { nudgeRest(-restStepSec()) }
        restPlusButton.setOnClickListener { nudgeRest(restStepSec()) }

        motionGateSeekBar.setOnProgressChangeListener { progress, _ ->
            val sec = progressToMotionSec(progress)
            viewModel.setMinMotionSec(sec)
            updateMotionGateUi()
            refreshTimingOverlay()
        }
        restGateSeekBar.setOnProgressChangeListener { progress, _ ->
            val sec = progressToRestSec(progress)
            viewModel.setMinRestSec(sec)
            updateRestGateUi()
            refreshTimingOverlay()
        }
    }

    private fun setMotionScale(maxSec: Float, keepValue: Boolean = true) {
        motionScaleMaxSec = maxSec
        val sec = if (keepValue) {
            viewModel.getMinMotionSec().coerceIn(0f, maxSec)
        } else {
            viewModel.getMinMotionSec().coerceAtMost(maxSec)
        }
        viewModel.setMinMotionSec(sec)
        motionGateSeekBar.max = GATE_SLIDER_STEPS
        motionGateSeekBar.progress = motionSecToProgress(sec)
        highlightScaleButtons(motionScaleButtons, MOTION_SCALES, maxSec)
        updateMotionGateUi()
        refreshTimingOverlay()
    }

    private fun setRestScale(maxSec: Float, keepValue: Boolean = true) {
        restScaleMaxSec = maxSec
        val sec = if (keepValue) {
            viewModel.getMinRestSec().coerceIn(0f, maxSec)
        } else {
            viewModel.getMinRestSec().coerceAtMost(maxSec)
        }
        viewModel.setMinRestSec(sec)
        restGateSeekBar.max = GATE_SLIDER_STEPS
        restGateSeekBar.progress = restSecToProgress(sec)
        highlightScaleButtons(restScaleButtons, REST_SCALES, maxSec)
        updateRestGateUi()
        refreshTimingOverlay()
    }

    private fun highlightScaleButtons(
        buttons: List<MaterialButton>,
        scales: FloatArray,
        selected: Float,
    ) {
        val accent = ContextCompat.getColor(requireContext(), R.color.metro_accent)
        val chrome = ContextCompat.getColor(requireContext(), R.color.metro_chrome_raised)
        buttons.forEachIndexed { index, button ->
            val on = kotlin.math.abs(scales[index] - selected) < 0.001f
            // WP10 tile: solid accent when selected, flat chrome when idle.
            button.backgroundTintList = ColorStateList.valueOf(if (on) accent else chrome)
            button.setTextColor(Color.WHITE)
            button.alpha = 1f
            button.cornerRadius = 0
            button.isCheckable = true
            button.isChecked = on
        }
    }

    private fun pickScale(sec: Float, scales: FloatArray): Float {
        for (s in scales) {
            if (sec <= s + 0.0001f) return s
        }
        return scales.last()
    }

    private fun motionStepSec(): Float =
        (motionScaleMaxSec / GATE_SLIDER_STEPS).coerceAtLeast(0.01f)

    private fun restStepSec(): Float =
        (restScaleMaxSec / GATE_SLIDER_STEPS).coerceAtLeast(0.01f)

    private fun nudgeMotion(delta: Float) {
        val next = (viewModel.getMinMotionSec() + delta)
            .coerceIn(0f, motionScaleMaxSec)
        viewModel.setMinMotionSec(next)
        motionGateSeekBar.progress = motionSecToProgress(next)
        updateMotionGateUi()
        refreshTimingOverlay()
    }

    private fun nudgeRest(delta: Float) {
        val next = (viewModel.getMinRestSec() + delta)
            .coerceIn(0f, restScaleMaxSec)
        viewModel.setMinRestSec(next)
        restGateSeekBar.progress = restSecToProgress(next)
        updateRestGateUi()
        refreshTimingOverlay()
    }

    private fun updateMotionGateUi() {
        val sec = viewModel.getMinMotionSec()
        motionGateSeekBar.labelText = if (sec <= 0f) {
            getString(R.string.motion_gate_label_off)
        } else {
            getString(R.string.motion_gate_label, sec)
        }
    }

    private fun updateRestGateUi() {
        val sec = viewModel.getMinRestSec()
        restGateSeekBar.labelText = if (sec <= 0f) {
            getString(R.string.rest_gate_label_off)
        } else {
            getString(R.string.rest_gate_label, sec)
        }
    }

    private fun refreshTimingOverlay() {
        waveformView.setTimingOverlay(
            enabled = timingPanel.isVisible,
            minMotionSec = viewModel.getMinMotionSec(),
            minRestSec = viewModel.getMinRestSec(),
            motionGateEnabled = viewModel.isMotionGateEnabled(),
            restGateEnabled = viewModel.isRestGateEnabled(),
        )
    }

    private fun setWaveformPaused(paused: Boolean) {
        waveformPaused = paused
        waveformView.setPaused(paused)
        viewModel.setDetectPaused(paused)
        refreshToolbarHighlights()
    }

    private fun progressToMotionSec(progress: Int): Float {
        if (motionGateSeekBar.max <= 0) return 0f
        return progress * motionScaleMaxSec / motionGateSeekBar.max
    }

    private fun motionSecToProgress(sec: Float): Int {
        if (motionScaleMaxSec <= 0f) return 0
        return ((sec / motionScaleMaxSec) * motionGateSeekBar.max)
            .toInt()
            .coerceIn(0, motionGateSeekBar.max)
    }

    private fun progressToRestSec(progress: Int): Float {
        if (restGateSeekBar.max <= 0) return 0f
        return progress * restScaleMaxSec / restGateSeekBar.max
    }

    private fun restSecToProgress(sec: Float): Int {
        if (restScaleMaxSec <= 0f) return 0
        return ((sec / restScaleMaxSec) * restGateSeekBar.max)
            .toInt()
            .coerceIn(0, restGateSeekBar.max)
    }

    private fun toggleChannelsPanel() {
        val opening = !channelsPanel.isVisible
        if (opening) {
            timingPanel.isVisible = false
            refreshTimingOverlay()
        }
        channelsPanel.isVisible = opening
        refreshToolbarHighlights()
    }

    private fun toggleTimingPanel() {
        val opening = !timingPanel.isVisible
        if (opening) {
            channelsPanel.isVisible = false
        }
        timingPanel.isVisible = opening
        refreshTimingOverlay()
        refreshToolbarHighlights()
    }

    private fun setToolbarButtonActive(button: ImageButton, active: Boolean) {
        val accent = ContextCompat.getColor(requireContext(), R.color.metro_accent)
        val idle = ContextCompat.getColor(requireContext(), R.color.metro_text)
        val chrome = ContextCompat.getColor(requireContext(), R.color.metro_chrome_raised)
        ImageViewCompat.setImageTintList(
            button,
            ColorStateList.valueOf(if (active) accent else idle),
        )
        // Active = accent glyph on chrome tile (WP selected app bar affordance).
        if (active) {
            button.setBackgroundColor(chrome)
        } else {
            button.setBackgroundResource(R.drawable.bg_metro_icon_button)
        }
        button.alpha = 1f
    }

    private fun refreshToolbarHighlights() {
        setToolbarButtonActive(pauseButton, waveformPaused)
        if (pathReplaying) {
            val accent = ContextCompat.getColor(requireContext(), R.color.metro_accent)
            ImageViewCompat.setImageTintList(clickArmButton, ColorStateList.valueOf(accent))
            clickArmButton.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.metro_chrome_raised),
            )
        } else {
            setToolbarButtonActive(clickArmButton, viewModel.isClickOutputEnabled())
        }
        setToolbarButtonActive(channelsButton, channelsPanel.isVisible)
        setToolbarButtonActive(timingButton, timingPanel.isVisible)
        setToolbarButtonActive(mouseButton, touchpadContainer.isVisible)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        setToolbarButtonActive(orientationButton, landscape)
        setToolbarButtonActive(statsButton, false)
        setToolbarButtonActive(settingsButton, false)
    }

    private fun startLightningBlink() {
        if (lightningBlinkAnimator?.isRunning == true) return
        val accent = ContextCompat.getColor(requireContext(), R.color.metro_accent)
        ImageViewCompat.setImageTintList(clickArmButton, ColorStateList.valueOf(accent))
        clickArmButton.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.metro_chrome_raised),
        )
        // imageAlpha only — avoid View.alpha invalidating the whole toolbar row.
        clickArmButton.imageAlpha = 255
        lightningBlinkAnimator = ObjectAnimator.ofInt(clickArmButton, "imageAlpha", 255, 40).apply {
            duration = 100L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopLightningBlink() {
        lightningBlinkAnimator?.cancel()
        lightningBlinkAnimator = null
        if (::clickArmButton.isInitialized) {
            clickArmButton.imageAlpha = 255
            refreshToolbarHighlights()
        }
    }

    private fun toggleOrientation() {
        val activity = requireActivity()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        activity.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        // Orientation change is handled in onConfigurationChanged (configChanges in manifest).
        refreshToolbarHighlights()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout()
        refreshToolbarHighlights()
    }

    private fun applyOrientationLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        activityValueText.isVisible = !landscape
        motionStateText.isVisible = !landscape
        if (!landscape) {
            toolbarStatusText.text = ""
        }
    }

    fun showTouchpad() {
        if (childFragmentManager.findFragmentByTag(TOUCHPAD_TAG) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.touchpadContainer, TouchpadFragment(), TOUCHPAD_TAG)
                .commitNow()
        }
        touchpadContainer.isVisible = true
        backCallback.isEnabled = true
        refreshToolbarHighlights()
    }

    fun closeTouchpad() {
        touchpadContainer.isVisible = false
        backCallback.isEnabled = false
        childFragmentManager.findFragmentByTag(TOUCHPAD_TAG)?.let {
            childFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss()
        }
        refreshToolbarHighlights()
    }

    private fun formatChannels(channels: Set<SensorChannel>): String {
        if (channels.isEmpty()) return getString(R.string.channel_off)
        return channels.joinToString(", ") { it.shortLabel }
    }

    private fun updateUsbStatus(state: EspLinkState) {
        val context = requireContext()
        when (state) {
            is EspLinkState.Connected -> {
                wasConnected = true
                stopStatusBlink()
                usbStatusDot.background = ContextCompat.getDrawable(context, R.drawable.status_dot_connected)
            }
            EspLinkState.RequestingPermission, EspLinkState.Connecting -> {
                stopStatusBlink()
                usbStatusDot.background = ContextCompat.getDrawable(context, R.drawable.status_dot_registered)
            }
            is EspLinkState.Error, EspLinkState.Disconnected -> {
                usbStatusDot.background = ContextCompat.getDrawable(context, R.drawable.status_dot_disconnected)
                if (wasConnected) {
                    startStatusBlink()
                }
            }
        }
    }

    private fun startStatusBlink() {
        if (statusBlinkAnimator?.isRunning == true) return
        statusBlinkAnimator = ObjectAnimator.ofFloat(usbStatusDot, View.ALPHA, 1f, 0.15f).apply {
            duration = 450L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopStatusBlink() {
        statusBlinkAnimator?.cancel()
        statusBlinkAnimator = null
        usbStatusDot.alpha = 1f
    }

    override fun onDestroyView() {
        stopStatusBlink()
        lightningBlinkAnimator?.cancel()
        lightningBlinkAnimator = null
        pathReplaying = false
        super.onDestroyView()
    }

    private fun flashClick() {
        clickFlashOverlay.visibility = View.VISIBLE
        clickFlashOverlay.alpha = 1f
        clickFlashOverlay.animate()
            .alpha(0f)
            .setDuration(150L)
            .withEndAction {
                clickFlashOverlay.visibility = View.GONE
            }
            .start()
    }

    companion object {
        private const val TOUCHPAD_TAG = "touchpad"
        private const val TAG_OFF = "off"
        private const val GATE_SLIDER_STEPS = 100
        private const val SMOOTH_SLIDER_STEPS = 50
        private const val SMOOTH_STEP_SEC = 0.01f
        private val MOTION_SCALES = floatArrayOf(0.1f, 0.5f, 5f)
        private val REST_SCALES = floatArrayOf(0.5f, 2f, 10f)
    }
}
