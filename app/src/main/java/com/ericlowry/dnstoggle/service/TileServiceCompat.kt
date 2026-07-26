package com.ericlowry.dnstoggle.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

object TileServiceCompat {
	private val debounceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
	private val debounceJobs = ConcurrentHashMap<ComponentName, Job>()

	fun requestListeningState(context: Context, componentName: ComponentName) {
		debounceJobs[componentName]?.cancel()
		debounceJobs[componentName] = debounceScope.launch {
			delay(500.milliseconds)
			try {
				TileService.requestListeningState(context.applicationContext, componentName)
			} catch (e: Exception) {
				Log.w("TileServiceCompat", "Failed to request tile listening state", e)
			}
		}
	}

	fun startActivityAndCollapse(tileService: TileService, intent: Intent) {
		val pendingIntent = PendingIntent.getActivity(
			tileService, 0, intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			tileService.startActivityAndCollapse(pendingIntent)
		} else {
			@Suppress(
				"StartActivityAndCollapseDeprecated",
				"DEPRECATION",
			) tileService.startActivityAndCollapse(intent)
		}
	}
}
