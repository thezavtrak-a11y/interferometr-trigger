package com.example.arduhud.tutorial

import com.example.arduhud.R

enum class TutorialPage { Main, Stats, Settings }

enum class TutorialChrome {
    None,
    Channels,
    Timing,
    Touchpad,
}

enum class TutorialCardSlot { Top, Bottom, BelowHole }

enum class TutorialAnim { None, Threshold, Channels, Timing }

enum class TutorialHole { View, WaveformGrid }

data class TutorialStepDef(
    val titleRes: Int,
    val bodyRes: Int,
    val targetId: Int,
    val page: TutorialPage,
    val chrome: TutorialChrome,
    val cardSlot: TutorialCardSlot = TutorialCardSlot.Bottom,
    val anim: TutorialAnim = TutorialAnim.None,
    val hole: TutorialHole = TutorialHole.View,
    val animatePage: Boolean = false,
)

object TutorialCatalog {
    val steps: List<TutorialStepDef> = listOf(
        TutorialStepDef(
            R.string.tutorial_title_waveform,
            R.string.tutorial_body_waveform,
            R.id.motionWaveform,
            TutorialPage.Main,
            TutorialChrome.None,
            cardSlot = TutorialCardSlot.Top,
            hole = TutorialHole.WaveformGrid,
        ),
        TutorialStepDef(
            R.string.tutorial_title_threshold,
            R.string.tutorial_body_threshold,
            R.id.motionWaveform,
            TutorialPage.Main,
            TutorialChrome.None,
            cardSlot = TutorialCardSlot.Top,
            anim = TutorialAnim.Threshold,
            hole = TutorialHole.WaveformGrid,
        ),
        TutorialStepDef(
            R.string.tutorial_title_status,
            R.string.tutorial_body_status,
            R.id.wifiConnectButton,
            TutorialPage.Main,
            TutorialChrome.None,
        ),
        TutorialStepDef(
            R.string.tutorial_title_pause,
            R.string.tutorial_body_pause,
            R.id.pauseButton,
            TutorialPage.Main,
            TutorialChrome.None,
        ),
        TutorialStepDef(
            R.string.tutorial_title_arm,
            R.string.tutorial_body_arm,
            R.id.clickArmButton,
            TutorialPage.Main,
            TutorialChrome.None,
        ),
        TutorialStepDef(
            R.string.tutorial_title_channels,
            R.string.tutorial_body_channels,
            R.id.channelsPanel,
            TutorialPage.Main,
            TutorialChrome.Channels,
            cardSlot = TutorialCardSlot.BelowHole,
            anim = TutorialAnim.Channels,
        ),
        TutorialStepDef(
            R.string.tutorial_title_timing,
            R.string.tutorial_body_timing,
            R.id.timingPanel,
            TutorialPage.Main,
            TutorialChrome.Timing,
            cardSlot = TutorialCardSlot.BelowHole,
            anim = TutorialAnim.Timing,
        ),
        TutorialStepDef(
            R.string.tutorial_title_click,
            R.string.tutorial_body_click,
            R.id.motionWaveform,
            TutorialPage.Main,
            TutorialChrome.Timing,
            hole = TutorialHole.WaveformGrid,
        ),
        TutorialStepDef(
            R.string.tutorial_title_mouse,
            R.string.tutorial_body_mouse,
            R.id.mouseButton,
            TutorialPage.Main,
            TutorialChrome.None,
        ),
        TutorialStepDef(
            R.string.tutorial_title_touchpad,
            R.string.tutorial_body_touchpad,
            R.id.touchpadSurface,
            TutorialPage.Main,
            TutorialChrome.Touchpad,
        ),
        TutorialStepDef(
            R.string.tutorial_title_touchpad_pos,
            R.string.tutorial_body_touchpad_pos,
            R.id.touchpadPosBar,
            TutorialPage.Main,
            TutorialChrome.Touchpad,
        ),
        TutorialStepDef(
            R.string.tutorial_title_stats,
            R.string.tutorial_body_stats,
            R.id.statsButton,
            TutorialPage.Main,
            TutorialChrome.None,
        ),
        TutorialStepDef(
            R.string.tutorial_title_journal,
            R.string.tutorial_body_journal,
            R.id.clickStatsRoot,
            TutorialPage.Stats,
            TutorialChrome.None,
            animatePage = true,
        ),
        TutorialStepDef(
            R.string.tutorial_title_settings,
            R.string.tutorial_body_settings,
            R.id.connectionTypeHeader,
            TutorialPage.Settings,
            TutorialChrome.None,
            animatePage = true,
        ),
        TutorialStepDef(
            R.string.tutorial_title_activity,
            R.string.tutorial_body_activity,
            R.id.activityValueText,
            TutorialPage.Main,
            TutorialChrome.None,
            animatePage = true,
        ),
        TutorialStepDef(
            R.string.tutorial_title_done,
            R.string.tutorial_body_done,
            R.id.showTutorialButton,
            TutorialPage.Settings,
            TutorialChrome.None,
            animatePage = true,
        ),
    )
}
