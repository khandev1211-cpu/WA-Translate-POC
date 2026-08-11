package com.watranslate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.watranslate.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("message") ?: return
            binding.tvResult.text = msg
            binding.tvStatus.text = "Status: finished"
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            binding.tvStatus.text = "Status: permission granted, starting capture…"

            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            binding.tvStatus.text = "Status: permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filter = IntentFilter("com.watranslate.app.STATUS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }

        binding.btnStartCapture.setOnClickListener {
            binding.tvResult.text = ""
            binding.tvStatus.text = "Status: requesting permission…"

            // IMPORTANT: before pressing this button in real testing, the user
            // needs to already be on an active WhatsApp call with the other
            // person talking, so there's something to try to capture.
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
}
