package com.ericlowry.dnstoggle.ui.controller

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.google.android.material.materialswitch.MaterialSwitch

class MiscSettingsController(
	private val activity: AppCompatActivity,
	private val viewModel: DnsViewModel,
	private val onOpenUrl: (String) -> Unit
) {
	private lateinit var switchShowToast: MaterialSwitch
	private lateinit var rowShowToast: View
	private lateinit var switchHideLauncher: MaterialSwitch
	private lateinit var rowHideLauncher: View
	private lateinit var tvHideLauncherSummary: TextView
	private lateinit var rowUsbDebuggingTile: View
	private lateinit var switchUsbDebuggingTile: MaterialSwitch
	private lateinit var tvUsbDebuggingTileSummary: TextView

	private var devHitCount = 0
	private var lastDevHitTime: Long = 0
	private var devToast: Toast? = null

	private val isTvDevice by lazy {
		val uiModeManager = activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
		uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
	}

	fun initialize(
		switchShowToast: MaterialSwitch,
		rowShowToast: View,
		switchHideLauncher: MaterialSwitch,
		rowHideLauncher: View,
		rowUsbDebuggingTile: View,
		switchUsbDebuggingTile: MaterialSwitch,
		tvUsbDebuggingTileSummary: TextView
	) {
		this.switchShowToast = switchShowToast
		this.rowShowToast = rowShowToast
		this.switchHideLauncher = switchHideLauncher
		this.rowHideLauncher = rowHideLauncher
		this.tvHideLauncherSummary = activity.findViewById(R.id.tvHideLauncherSummary)
		this.rowUsbDebuggingTile = rowUsbDebuggingTile
		this.switchUsbDebuggingTile = switchUsbDebuggingTile
		this.tvUsbDebuggingTileSummary = tvUsbDebuggingTileSummary

		setupOtherSettings()
		setupFooter()
		setupUsbDebuggingTile()
		setupVersionClick()

		val prefs = (activity.application as DnsToggleApplication).getPrefs()
		if (prefs.getBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, false)) {
			rowUsbDebuggingTile.visibility = View.VISIBLE
		}

		if (isTvDevice) {
			rowHideLauncher.isEnabled = false
			rowHideLauncher.alpha = 0.5f
			switchHideLauncher.isEnabled = false
			tvHideLauncherSummary.text = activity.getString(R.string.hide_launcher_icon_tv_summary)

			updateLauncherComponentState(isHidden = false)
			viewModel.setHideLauncherIcon(false)
		}
	}

	fun observeViewModel() {
		viewModel.showToastEnabled.observe(activity) { enabled ->
			switchShowToast.isChecked = enabled
		}

		viewModel.hideLauncherIcon.observe(activity) { isHidden ->
			if (!isTvDevice) {
				switchHideLauncher.isChecked = isHidden
				updateLauncherComponentState(isHidden)
			} else {
				switchHideLauncher.isChecked = false
			}
		}
	}

	private fun setupOtherSettings() {
		rowShowToast.setOnClickListener { switchShowToast.toggle() }
		switchShowToast.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setShowToast(isChecked)
		}

		rowHideLauncher.setOnClickListener { switchHideLauncher.toggle() }
		switchHideLauncher.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setHideLauncherIcon(isChecked)
		}
	}

	private fun setupUsbDebuggingTile() {
		val prefs = (activity.application as DnsToggleApplication).getPrefs()

		switchUsbDebuggingTile.isChecked =
			prefs.getBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, false)

		switchUsbDebuggingTile.setOnCheckedChangeListener { _, isChecked ->
			prefs.edit { putBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, isChecked) }

			if (!isChecked) {
				rowUsbDebuggingTile.visibility = View.GONE
				devHitCount = 0
			}

			(activity.application as DnsToggleApplication).updateUsbDebuggingTileAvailability()
		}
	}

	private fun setupVersionClick() {
		val tvAppVersion = activity.findViewById<TextView>(R.id.tvAppVersion)
		tvAppVersion.setOnClickListener {
			val prefs = (activity.application as DnsToggleApplication).getPrefs()
			if (prefs.getBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, false)) {
				return@setOnClickListener
			}

			val isDevMode = Settings.Global.getInt(
				activity.contentResolver,
				Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
				0
			) != 0

			if (!isDevMode) {
				return@setOnClickListener
			}

			val currentTime = System.currentTimeMillis()
			if (lastDevHitTime == 0L || (currentTime - lastDevHitTime) > 500) {
				devHitCount = 1
			} else {
				devHitCount++
			}
			lastDevHitTime = currentTime

			if (devHitCount in 1..4) {
				val remaining = 5 - devHitCount
				if (remaining <= 3) {
					devToast?.cancel()
					devToast = Toast.makeText(
						activity,
						activity.getString(R.string.usb_debugging_tile_steps, remaining),
						Toast.LENGTH_SHORT
					)
					devToast?.show()
				}
			} else if (devHitCount >= 5) {
				devToast?.cancel()
				devToast = Toast.makeText(
					activity,
					activity.getString(R.string.usb_debugging_tile_enabled),
					Toast.LENGTH_SHORT
				)
				devToast?.show()
				prefs.edit { putBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, true) }

				rowUsbDebuggingTile.visibility = View.VISIBLE
				switchUsbDebuggingTile.isChecked = true

				(activity.application as DnsToggleApplication).updateUsbDebuggingTileAvailability()
			}
		}
	}

	private fun setupFooter() {
		activity.findViewById<Button>(R.id.btnGithub).setOnClickListener {
			onOpenUrl("https://github.com/ELowry/DNSToggle")
		}
		activity.findViewById<Button>(R.id.btnSupport).setOnClickListener {
			onOpenUrl("https://github.com/ELowry/DNSToggle/issues/new/choose")
		}
	}

	private fun updateLauncherComponentState(isHidden: Boolean) {
		val componentName = ComponentName(activity, "${activity.packageName}.LauncherActivity")
		val newState = if (isHidden) {
			PackageManager.COMPONENT_ENABLED_STATE_DISABLED
		} else {
			PackageManager.COMPONENT_ENABLED_STATE_ENABLED
		}

		if (activity.packageManager.getComponentEnabledSetting(componentName) != newState) {
			activity.packageManager.setComponentEnabledSetting(
				componentName,
				newState,
				PackageManager.DONT_KILL_APP
			)
		}
	}
}
