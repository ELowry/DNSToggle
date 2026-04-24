package com.ericlowry.dnstoggle

import android.app.Application
import com.google.android.material.color.DynamicColors

class DnsToggleApplication : Application() {
	override fun onCreate() {
		super.onCreate()
		DynamicColors.applyToActivitiesIfAvailable(this)
	}
}
