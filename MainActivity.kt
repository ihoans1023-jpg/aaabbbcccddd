package com.example.precisionautoclicker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.precisionautoclicker.overlay.OverlayService
import com.example.precisionautoclicker.service.AutoClickAccessibilityService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessStatus: TextView
    private lateinit var cbAntiDetection: CheckBox
    private lateinit var etGlobalCycles: EditText
    private lateinit var etMinCooldown: EditText
    private lateinit var cbPauseOnExternalTouch: CheckBox
    private lateinit var etExternalResumeDelayMs: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvAccessStatus = findViewById(R.id.tvAccessStatus)
        cbAntiDetection = findViewById(R.id.cbAntiDetection)
        etGlobalCycles = findViewById(R.id.etGlobalCycles)
        etMinCooldown = findViewById(R.id.etMinCooldownMs)
        cbPauseOnExternalTouch = findViewById(R.id.cbPauseOnExternalTouch)
        etExternalResumeDelayMs = findViewById(R.id.etExternalResumeDelayMs)

        findViewById<Button>(R.id.btnRequestOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnLaunchOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "다른 앱 위에 표시 권한을 먼저 허용해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!AutoClickAccessibilityService.isServiceEnabled()) {
                Toast.makeText(this, "접근성 서비스를 먼저 활성화해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cycles = etGlobalCycles.text.toString().toIntOrNull() ?: 0
            val cooldown = etMinCooldown.text.toString().toLongOrNull() ?: 12L
            val pauseOnExt = cbPauseOnExternalTouch.isChecked
            val resumeDelay = etExternalResumeDelayMs.text.toString().toLongOrNull() ?: 1500L
            val antiDetect = cbAntiDetection.isChecked

            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_GLOBAL_CYCLES, cycles)
                putExtra(OverlayService.EXTRA_MIN_COOLDOWN_MS, cooldown)
                putExtra(OverlayService.EXTRA_PAUSE_ON_EXT_TOUCH, pauseOnExt)
                putExtra(OverlayService.EXTRA_EXT_RESUME_DELAY_MS, resumeDelay)
                putExtra(OverlayService.EXTRA_ANTI_DETECTION, antiDetect)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            moveTaskToBack(true)
        }

        lifecycleScope.launch {
            AutoClickAccessibilityService.instance.collect {
                refreshPermissionUI()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUI()
    }

    private fun refreshPermissionUI() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = AutoClickAccessibilityService.isServiceEnabled()

        tvOverlayStatus.text = if (overlayOk) "오버레이 권한: 허용됨" else "오버레이 권한: 필요함"
        tvAccessStatus.text = if (accessOk) "접근성 서비스: 활성화됨" else "접근성 서비스: 비활성화됨"
    }
}
