package com.watranslate.app

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.watranslate.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("wa_translate_prefs", Context.MODE_PRIVATE)
        binding.etApiKey.setText(prefs.getString("api_key", ""))

        binding.btnSave.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            prefs.edit().putString("api_key", key).apply()
            binding.tvSaved.text = "Saved."
            finish()
        }
    }
}
