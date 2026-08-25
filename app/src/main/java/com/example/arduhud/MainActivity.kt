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
import com.example.arduhud.ui.MainPagerAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: AppViewModel by viewModels()
    private lateinit var viewPager: ViewPager2

    private val settingsBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            openSensorScreen()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Wi-Fi connect will retry after grant */ }

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

        requestWifiPermissions()

        onBackPressedDispatcher.addCallback(this, settingsBackCallback)

        viewPager = findViewById(R.id.viewPager)
        viewPager.adapter = MainPagerAdapter(this)
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

    fun openSensorScreen() {
        if (::viewPager.isInitialized) viewPager.setCurrentItem(0, false)
    }

    fun openSettingsScreen() {
        if (::viewPager.isInitialized) viewPager.setCurrentItem(1, false)
    }

    fun openClickStatsScreen() {
        if (::viewPager.isInitialized) viewPager.setCurrentItem(2, false)
    }

    fun isSettingsScreen(): Boolean {
        return ::viewPager.isInitialized && viewPager.currentItem == 1
    }

    private fun requestWifiPermissions() {
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
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
