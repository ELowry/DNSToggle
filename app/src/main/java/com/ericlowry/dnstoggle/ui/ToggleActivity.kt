package com.ericlowry.dnstoggle.ui

import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsManager

class ToggleActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

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
