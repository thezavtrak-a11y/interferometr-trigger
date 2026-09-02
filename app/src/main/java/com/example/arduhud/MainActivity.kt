package com.example.arduhud

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.example.arduhud.tutorial.TutorialGuide
import com.example.arduhud.tutorial.TutorialOverlayView
import com.example.arduhud.tutorial.TutorialPrefs
import com.example.arduhud.ui.MainFragment
import com.example.arduhud.ui.MainPagerAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: AppViewModel by viewModels()
    private lateinit var viewPager: ViewPager2
    private var tutorialGuide: TutorialGuide? = null
    private var skipFirstRunTutorial = false

    private val settingsBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (tutorialGuide?.isRunning == true) return
            openSensorScreen()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onBluetoothReady()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level < 0) return
            viewModel.onBatteryPercent((level * 100) / scale)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        requestLinkPermissions()

        onBackPressedDispatcher.addCallback(this, settingsBackCallback)

        viewPager = findViewById(R.id.viewPager)
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 2
        viewPager.isUserInputEnabled = false
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                settingsBackCallback.isEnabled = position != 0
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.keepScreenOn.collect { enabled ->
                    if (enabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        val overlay = findViewById<TutorialOverlayView>(R.id.tutorialOverlay)
        tutorialGuide = TutorialGuide(this, overlay, viewModel)

        handleBleDebugIntent(intent)
        maybeStartFirstRunTutorial()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBleDebugIntent(intent)
    }

    /**
     * adb: am start -n com.example.arduhud/.MainActivity \
     *   --ez ble_register true --es ble_host AA:BB:CC:DD:EE:FF
     */
    private fun handleBleDebugIntent(intent: Intent?) {
        if (intent == null) return
        val register = intent.getBooleanExtra(EXTRA_BLE_REGISTER, false)
        val host = intent.getStringExtra(EXTRA_BLE_HOST)
        val openPad = intent.getBooleanExtra(EXTRA_OPEN_TOUCHPAD, false)
        if (!register && host.isNullOrBlank() && !openPad) return
        skipFirstRunTutorial = true
        intent.removeExtra(EXTRA_BLE_REGISTER)
        intent.removeExtra(EXTRA_BLE_HOST)
        intent.removeExtra(EXTRA_OPEN_TOUCHPAD)
        if (openPad) {
            openTouchpad()
            return
        }
        openSettingsScreen()
        viewPager.post {
            if (register) {
                viewModel.registerDirectBle(this)
            }
            if (!host.isNullOrBlank()) {
                viewPager.postDelayed({
                    viewModel.connectBleHost(host)
                }, 8_000L)
            } else if (register) {
                viewPager.postDelayed({
                    viewModel.connectPreferredBleHost()
                }, 8_000L)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = registerReceiver(batteryReceiver, filter)
        sticky?.let { batteryReceiver.onReceive(this, it) }
    }

    override fun onStop() {
        unregisterReceiver(batteryReceiver)
        super.onStop()
    }

    fun openSensorScreen(animated: Boolean = false) {
        if (::viewPager.isInitialized) viewPager.setCurrentItem(0, animated)
    }

    fun openTouchpad() {
        openSensorScreen()
        if (!::viewPager.isInitialized) return
        viewPager.post {
            val main = findMainFragment()
            if (main != null) {
                main.showTouchpad()
            } else {
                viewPager.postDelayed({ findMainFragment()?.showTouchpad() }, 200)
            }
        }
    }

    fun findMainFragment(): MainFragment? {
        return supportFragmentManager.fragments
            .filterIsInstance<MainFragment>()
            .firstOrNull()
    }

    fun startTutorial() {
        if (tutorialGuide?.isRunning == true) return
        openSensorScreen()
        viewPager.post {
            viewPager.postDelayed({
                if (isDestroyed || isFinishing) return@postDelayed
                tutorialGuide?.start()
            }, 120)
        }
    }

    private fun maybeStartFirstRunTutorial() {
        if (skipFirstRunTutorial) return
        if (TutorialPrefs.hasSeen(this)) return
        viewPager.post {
            viewPager.postDelayed({
                if (isDestroyed || isFinishing) return@postDelayed
                if (skipFirstRunTutorial || TutorialPrefs.hasSeen(this)) return@postDelayed
                startTutorial()
            }, 500)
        }
    }

    fun openSettingsScreen(animated: Boolean = false) {
        if (::viewPager.isInitialized) viewPager.setCurrentItem(1, animated)
    }

    fun openClickStatsScreen(animated: Boolean = false) {
        if (::viewPager.isInitialized) viewPager.setCurrentItem(2, animated)
    }

    fun isSettingsScreen(): Boolean {
        return ::viewPager.isInitialized && viewPager.currentItem == 1
    }

    private fun requestLinkPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.BLUETOOTH_ADVERTISE
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    companion object {
        const val EXTRA_BLE_REGISTER = "ble_register"
        const val EXTRA_BLE_HOST = "ble_host"
        const val EXTRA_OPEN_TOUCHPAD = "open_touchpad"
    }
}
