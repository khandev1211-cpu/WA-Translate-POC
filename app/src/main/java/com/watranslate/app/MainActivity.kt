package com.watranslate.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.watranslate.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkOverlayThenStart()
        } else {
            binding.tvStatus.text = "Status: mic permission denied"
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startTranslateService()
        } else {
            binding.tvStatus.text = "Status: overlay permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnStartCapture.setOnClickListener {
            if (isServiceRunning) {
                stopTranslateService()
            } else {
                val prefs = getSharedPreferences("wa_translate_prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("api_key", "")
                if (apiKey.isNullOrBlank()) {
                    binding.tvStatus.text = "Status: pehle Settings mein API key daalein"
                    return@setOnClickListener
                }
                checkMicThenProceed()
            }
        }

        updateButtonState()
    }

    private fun checkMicThenProceed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            checkOverlayThenStart()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkOverlayThenStart() {
        if (Settings.canDrawOverlays(this)) {
            startTranslateService()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startTranslateService() {
        val prefs = getSharedPreferences("wa_translate_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""

        val serviceIntent = Intent(this, TranslateService::class.java).apply {
            putExtra("api_key", apiKey)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true
        updateButtonState()
        binding.tvStatus.text = "Status: running — call ko loudspeaker pe daalein"
    }

    private fun stopTranslateService() {
        val stopIntent = Intent(this, TranslateService::class.java).apply {
            action = TranslateService.ACTION_STOP
        }
        startService(stopIntent)
        isServiceRunning = false
        updateButtonState()
        binding.tvStatus.text = "Status: stopped"
    }

    private fun updateButtonState() {
        binding.btnStartCapture.text = if (isServiceRunning) "Stop Translation" else "Start Translation"
    }
}
