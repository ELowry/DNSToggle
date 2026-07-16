package com.ericlowry.dnstoggle.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.ui.MainActivity

class UsbDebuggingTileService : TileService() {

	private var isProcessing = false
	private val handler = Handler(Looper.getMainLooper())

	override fun onStartListening() {
		super.onStartListening()
		updateTile()
	}

	override fun onClick() {
		super.onClick()
		if (isProcessing) return

		val isEnabled = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
		val nextState = if (isEnabled) 0 else 1

		isProcessing = true
		updateTile()

		handler.postDelayed(
			{
				isProcessing = false
				updateTile()
			},
			1500,
		)

		try {
			Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, nextState)
		} catch (_: SecurityException) {
			isProcessing = false
			launchMainActivityWithPermissionPrompt()
		}
	}

	private fun updateTile() {
		val tile = qsTile ?: return
		val isEnabled = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) != 0

		if (isProcessing) {
			tile.state = Tile.STATE_UNAVAILABLE
			tile.icon = Icon.createWithResource(this, R.drawable.ic_ellipsis)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				tile.subtitle =
					if (isEnabled) getString(R.string.disabling) else getString(R.string.enabling)
			}
		} else {
			tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
			tile.icon = Icon.createWithResource(this, R.drawable.ic_tool)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				tile.subtitle = null
			}
		}
		tile.updateTile()
	}

	private fun launchMainActivityWithPermissionPrompt() {
		val intent = Intent(this, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			putExtra("show_permission_dialog", true)
		}

		TileServiceCompat.startActivityAndCollapse(this, intent)
	}
}
