package com.ericlowry.dnstoggle

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object TileServiceCompat {
	fun requestListeningState(context: Context, componentName: ComponentName) {
		try {
			TileService.requestListeningState(context, componentName)
		} catch (e: Exception) {
			android.util.Log.w("TileServiceCompat", "Failed to request tile listening state", e)
		}
	}
}
