package com.ericlowry.dnstoggle.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log

object TileServiceCompat {
	fun requestListeningState(context: Context, componentName: ComponentName) {
		try {
			TileService.requestListeningState(context, componentName)
		} catch (e: Exception) {
			Log.w("TileServiceCompat", "Failed to request tile listening state", e)
		}
	}

	fun startActivityAndCollapse(tileService: TileService, intent: Intent) {
		val pendingIntent = PendingIntent.getActivity(
			tileService, 0, intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			tileService.startActivityAndCollapse(pendingIntent)
		} else {
			@Suppress(
				"StartActivityAndCollapseDeprecated",
				"DEPRECATION"
			) tileService.startActivityAndCollapse(intent)
		}
	}
}
