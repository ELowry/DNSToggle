package com.ericlowry.dnstoggle.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsManager
import com.ericlowry.dnstoggle.data.repository.AppSettingsRepository

class ToggleActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val authMode = AppSettingsRepository.authMode.value
		if (authMode == Constants.AuthMode.ACTION_ONLY || authMode == Constants.AuthMode.ALWAYS) {
			val intent = Intent(this, QsAuthActivity::class.java).apply {
				action = Constants.ACTION_TOGGLE
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			}
			startActivity(intent)
			finish()
			return
		}

		val currentMode =
			Settings.Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		val shouldEnable = currentMode != Constants.DNS_MODE_HOSTNAME

		DnsManager.togglePrivateDns(
			context = this,
			enabled = shouldEnable,
			forceFeedback = true,
			isFromTile = true
		)

		finish()
	}
}
