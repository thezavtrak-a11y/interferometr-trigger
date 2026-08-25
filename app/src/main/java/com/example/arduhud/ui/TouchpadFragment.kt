package com.example.arduhud.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.arduhud.AppViewModel
import com.example.arduhud.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TouchpadFragment : Fragment() {

    private val viewModel: AppViewModel by activityViewModels()

    private var lastX = 0f
    private var lastY = 0f
    private var lastTwoFingerX = 0f
    private var lastTwoFingerY = 0f
    private var longPressTriggered = false
    private var twoFingerActive = false
    private var scrollAccumulator = 0f
    private var horizontalSwipeConsumed = false

    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var touchpadSurface: View
    private lateinit var touchpadHint: View
    private lateinit var rememberedPosLabel: TextView
    private lateinit var findPosButton: MaterialButton
    private lateinit var rememberPosButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_touchpad, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        touchpadSurface = view.findViewById(R.id.touchpadSurface)
        touchpadHint = view.findViewById(R.id.touchpadHint)
        rememberedPosLabel = view.findViewById(R.id.rememberedPosLabel)
        findPosButton = view.findViewById(R.id.findPosButton)
        rememberPosButton = view.findViewById(R.id.rememberPosButton)
        view.findViewById<View>(R.id.touchpadCloseButton).setOnClickListener {
            (parentFragment as? MainFragment)?.closeTouchpad()
        }

        findPosButton.setOnClickListener {
            if (!ensureConnected()) return@setOnClickListener
            if (!viewModel.findTopRightOrigin()) {
                Snackbar.make(view, R.string.touchpad_pos_finding, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Snackbar.make(view, R.string.touchpad_pos_finding, Snackbar.LENGTH_SHORT).show()
        }

        rememberPosButton.setOnClickListener {
            if (!ensureConnected()) return@setOnClickListener
            if (viewModel.rememberCurrentPosition()) {
                Snackbar.make(view, R.string.touchpad_pos_remembered, Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(view, R.string.touchpad_pos_need_find, Snackbar.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        viewModel.trackedPos,
                        viewModel.rememberedPos,
                    ) { tracked, remembered -> tracked to remembered }
                        .collect { (tracked, remembered) ->
                            rememberedPosLabel.text = when {
                                tracked == null -> getString(R.string.touchpad_pos_none)
                                remembered != null -> getString(
                                    R.string.touchpad_pos_saved,
                                    remembered.first,
                                    remembered.second,
                                    tracked.first,
                                    tracked.second,
                                )
                                else -> getString(
                                    R.string.touchpad_pos_tracking,
                                    tracked.first,
                                    tracked.second,
                                )
                            }
                        }
                }
                launch {
                    var wasFinding = false
                    viewModel.findingOrigin.collect { finding ->
                        findPosButton.isEnabled = !finding
                        rememberPosButton.isEnabled = !finding
                        if (wasFinding && !finding && viewModel.trackedPos.value != null) {
                            view?.let {
                                Snackbar.make(it, R.string.touchpad_pos_found, Snackbar.LENGTH_SHORT)
                                    .show()
                            }
                        }
                        wasFinding = finding
                    }
                }
            }
        }

        gestureDetector = GestureDetectorCompat(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (twoFingerActive || !ensureConnected()) return true
                    viewModel.sendClick()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (twoFingerActive) return
                    longPressTriggered = true
                    if (!ensureConnected()) return
                    viewModel.sendRightClick()
                }
            },
        )

        touchpadSurface.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    twoFingerActive = false
                    lastX = event.x
                    lastY = event.y
                    gestureDetector.onTouchEvent(event)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        twoFingerActive = true
                        horizontalSwipeConsumed = false
                        scrollAccumulator = 0f
                        val center = twoFingerCenter(event)
                        lastTwoFingerX = center.first
                        lastTwoFingerY = center.second
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        handleTwoFingerMove(event)
                    } else if (!twoFingerActive && !longPressTriggered) {
                        handleSingleFingerMove(event)
                        gestureDetector.onTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount - 1 < 2) {
                        twoFingerActive = false
                        horizontalSwipeConsumed = false
                        scrollAccumulator = 0f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    twoFingerActive = false
                    horizontalSwipeConsumed = false
                    scrollAccumulator = 0f
                    gestureDetector.onTouchEvent(event)
                }
            }
            true
        }
    }

    private fun handleSingleFingerMove(event: MotionEvent) {
        if (!viewModel.isConnected() || viewModel.isFindingOrigin()) return
        val dx = ((event.x - lastX) * MOVE_SCALE).roundToInt()
        val dy = ((event.y - lastY) * MOVE_SCALE).roundToInt()
        if (dx != 0 || dy != 0) {
            viewModel.sendMouseMove(dx, dy)
            lastX = event.x
            lastY = event.y
            touchpadHint.visibility = View.GONE
        }
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        if (!viewModel.isConnected() || viewModel.isFindingOrigin()) return

        val center = twoFingerCenter(event)
        val deltaX = center.first - lastTwoFingerX
        val deltaY = center.second - lastTwoFingerY
        lastTwoFingerX = center.first
        lastTwoFingerY = center.second

        if (deltaX == 0f && deltaY == 0f) return
        touchpadHint.visibility = View.GONE

        if (abs(deltaY) >= abs(deltaX)) {
            scrollAccumulator -= deltaY * SCROLL_SCALE
            val scrollSteps = scrollAccumulator.roundToInt()
            if (scrollSteps != 0) {
                viewModel.sendScroll(scrollSteps)
                scrollAccumulator -= scrollSteps
            }
            return
        }

        if (horizontalSwipeConsumed) return

        when {
            deltaX >= HORIZONTAL_SWIPE_THRESHOLD -> {
                viewModel.sendButton4Click()
                horizontalSwipeConsumed = true
            }
            deltaX <= -HORIZONTAL_SWIPE_THRESHOLD -> {
                viewModel.sendButton5Click()
                horizontalSwipeConsumed = true
            }
        }
    }

    private fun twoFingerCenter(event: MotionEvent): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        val count = minOf(event.pointerCount, 2)
        for (i in 0 until count) {
            sumX += event.getX(i)
            sumY += event.getY(i)
        }
        return sumX / count to sumY / count
    }

    private fun ensureConnected(): Boolean {
        if (viewModel.isConnected()) return true
        view?.let {
            Snackbar.make(it, R.string.touchpad_not_connected, Snackbar.LENGTH_SHORT).show()
        }
        return false
    }

    companion object {
        /** Finger→HID gain (precision off). Raise if still sluggish vs CORNER. */
        private const val MOVE_SCALE = 2.5f
        private const val SCROLL_SCALE = 0.08f
        private const val HORIZONTAL_SWIPE_THRESHOLD = 24f
    }
}
