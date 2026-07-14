package com.ericlowry.dnstoggle.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

class DeveloperOptionsActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		try {
			startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
		} catch (_: Exception) {
			startActivity(Intent(Settings.ACTION_SETTINGS))
		}
		finish()
	}
}