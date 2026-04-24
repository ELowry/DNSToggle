package com.ericlowry.dnstoggle

import android.app.AlertDialog
import android.provider.Settings
import android.graphics.drawable.Icon
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DnsToggleService : TileService() {

	override fun onClick() {
		super.onClick()

		if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
			showPermissionDialog()
			return
		}

		val resolver = contentResolver

		try {
			val currentMode = Settings.Global.getString(resolver, "private_dns_mode")

			if (currentMode == "hostname") {
				Settings.Global.putString(resolver, "private_dns_mode", "opportunistic")
				updateTileState(Tile.STATE_INACTIVE)
			} else {
				Settings.Global.putString(resolver, "private_dns_mode", "hostname")
				updateTileState(Tile.STATE_ACTIVE)
			}
		} catch (e: SecurityException) {
			e.printStackTrace()
		}
	}

	override fun onStartListening() {
		super.onStartListening()

		if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
			updateTileState(Tile.STATE_UNAVAILABLE)
			return
		}

		try {
			val currentMode = Settings.Global.getString(contentResolver, "private_dns_mode")
			val state = if (currentMode == "hostname") Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
			updateTileState(state)
		} catch (e: SecurityException) {
			updateTileState(Tile.STATE_UNAVAILABLE)
		}
	}

	private fun updateTileState(state: Int) {
		qsTile?.let { tile ->
			tile.state = state

			val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
			val dynamicName = prefs.getString("dynamic_app_name", getString(R.string.app_name))

			tile.label = dynamicName

			val iconRes = if (state == Tile.STATE_ACTIVE) {
				R.drawable.ic_qs_dns
			} else {
				R.drawable.ic_qs_dns_inactive
			}

			tile.icon = Icon.createWithResource(this, iconRes)

			tile.updateTile()
		}
	}

	private fun showPermissionDialog() {
		val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
			.setTitle(getString(R.string.permission_required))
			.setMessage(getString(R.string.permission_message))
			.setPositiveButton(getString(R.string.ok), null)
			.create()

		showDialog(dialog)
	}
}