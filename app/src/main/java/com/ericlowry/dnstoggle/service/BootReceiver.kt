package com.ericlowry.dnstoggle.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ericlowry.dnstoggle.DnsToggleApplication

class BootReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
			intent.action == "android.intent.action.QUICKBOOT_POWERON"
		) {

			val application = context.applicationContext as DnsToggleApplication
			application.updateWifiMonitoringRegistration()
		}
	}
}
