package com.ericlowry.dnstoggle.ui.controller

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.util.setConditionalVisibility
import com.ericlowry.dnstoggle.util.setDimmedEnabled
import com.google.android.material.materialswitch.MaterialSwitch

class MiscSettingsController(
	private val activity: AppCompatActivity,
	private val viewModel: DnsViewModel,
	private val onOpenUrl: (String) -> Unit,
	private val onUsbToggleVisibilityChanged: () -> Unit
) {
	private lateinit var switchShowToast: MaterialSwitch
	private lateinit var rowShowToast: View
	private lateinit var switchEnableStrictOff: MaterialSwitch
	private lateinit var rowEnableStrictOff: View
	private lateinit var tvDefaultOffModeValue: TextView
	private lateinit var rowDefaultOffMode: View
	private lateinit var layoutStrictOffSubset: View
	private lateinit var switchHideLauncher: MaterialSwitch
	private lateinit var rowHideLauncher: View
	private lateinit var tvHideLauncherSummary: TextView
	private lateinit var rowUsbDebuggingTile: View
	private lateinit var switchUsbDebuggingTile: MaterialSwitch
	private lateinit var tvUsbDebuggingTileSummary: TextView
	private lateinit var btnWhatsNew: Button

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
		switchEnableStrictOff: MaterialSwitch,
		rowEnableStrictOff: View,
		tvDefaultOffModeValue: TextView,
		rowDefaultOffMode: View,
		layoutStrictOffSubset: View,
		switchHideLauncher: MaterialSwitch,
		rowHideLauncher: View,
		rowUsbDebuggingTile: View,
		switchUsbDebuggingTile: MaterialSwitch,
		tvUsbDebuggingTileSummary: TextView
	) {
		this.switchShowToast = switchShowToast
		this.rowShowToast = rowShowToast
		this.switchEnableStrictOff = switchEnableStrictOff
		this.rowEnableStrictOff = rowEnableStrictOff
		this.tvDefaultOffModeValue = tvDefaultOffModeValue
		this.rowDefaultOffMode = rowDefaultOffMode
		this.layoutStrictOffSubset = layoutStrictOffSubset
		this.switchHideLauncher = switchHideLauncher
		this.rowHideLauncher = rowHideLauncher
		this.tvHideLauncherSummary = activity.findViewById(R.id.tvHideLauncherSummary)
		this.rowUsbDebuggingTile = rowUsbDebuggingTile
		this.switchUsbDebuggingTile = switchUsbDebuggingTile
		this.tvUsbDebuggingTileSummary = tvUsbDebuggingTileSummary
		this.btnWhatsNew = activity.findViewById(R.id.btnWhatsNew)

		setupOtherSettings()
		setupFooter()
		setupUsbDebuggingTile()
		setupVersionClick()
		setupWhatsNew()

		val prefs = (activity.application as DnsToggleApplication).getPrefs()
		if (prefs.getBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, false)) {
			rowUsbDebuggingTile.setConditionalVisibility(true)
		}

		if (isTvDevice) {
			rowHideLauncher.setDimmedEnabled(false)
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

		viewModel.enableStrictOffOption.observe(activity) { enabled ->
			switchEnableStrictOff.isChecked = enabled
			val container = activity.findViewById<ViewGroup>(R.id.contentWrapper)
			layoutStrictOffSubset.setConditionalVisibility(enabled, container)
		}

		viewModel.defaultOffMode.observe(activity) { mode ->
			tvDefaultOffModeValue.text = when (mode) {
				Constants.DNS_MODE_OFF -> activity.getString(R.string.mode_disabled)
				else -> activity.getString(R.string.mode_automatic)
			}
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

		rowEnableStrictOff.setOnClickListener { switchEnableStrictOff.toggle() }
		switchEnableStrictOff.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setEnableStrictOffOption(isChecked)
		}

		rowDefaultOffMode.setOnClickListener {
			if (!switchEnableStrictOff.isChecked) return@setOnClickListener

			val options = arrayOf(
				activity.getString(R.string.mode_automatic),
				activity.getString(R.string.mode_disabled)
			)
			val modes = arrayOf(
				Constants.DNS_MODE_OPPORTUNISTIC,
				Constants.DNS_MODE_OFF
			)
			val checkedItem = modes.indexOf(viewModel.defaultOffMode.value)

			com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.default_off_mode_title)
				.setSingleChoiceItems(options, checkedItem) { dialog, which ->
					viewModel.setDefaultOffMode(modes[which])
					dialog.dismiss()
				}
				.setNegativeButton(R.string.cancel, null)
				.show()
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

		rowUsbDebuggingTile.setOnClickListener {
			switchUsbDebuggingTile.toggle()
		}

		switchUsbDebuggingTile.setOnCheckedChangeListener { _, isChecked ->
			prefs.edit { putBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, isChecked) }

			if (!isChecked) {
				rowUsbDebuggingTile.setConditionalVisibility(
					false,
					rowUsbDebuggingTile.parent as? ViewGroup
				)
				devHitCount = 0
				onUsbToggleVisibilityChanged()
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
			if (lastDevHitTime == 0L || (currentTime - lastDevHitTime) > Constants.DEV_HIT_RESET_THRESHOLD_MS) {
				devHitCount = 1
			} else {
				devHitCount++
			}
			lastDevHitTime = currentTime

			if (devHitCount in 1 until Constants.USB_DEBUGGING_TILE_THRESHOLD) {
				val remaining = Constants.USB_DEBUGGING_TILE_THRESHOLD - devHitCount
				if (remaining <= 3) {
					devToast?.cancel()
					devToast = Toast.makeText(
						activity,
						activity.resources.getQuantityString(
							R.plurals.usb_debugging_tile_steps,
							remaining,
							remaining
						),
						Toast.LENGTH_SHORT
					)
					devToast?.show()
				}
			} else if (devHitCount >= Constants.USB_DEBUGGING_TILE_THRESHOLD) {
				devToast?.cancel()
				devToast = Toast.makeText(
					activity,
					activity.getString(R.string.usb_debugging_tile_enabled),
					Toast.LENGTH_SHORT
				)
				devToast?.show()
				prefs.edit { putBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, true) }

				rowUsbDebuggingTile.setConditionalVisibility(
					true,
					rowUsbDebuggingTile.parent as? ViewGroup
				)
				switchUsbDebuggingTile.isChecked = true
				onUsbToggleVisibilityChanged()

				(activity.application as DnsToggleApplication).updateUsbDebuggingTileAvailability()
			}
		}
	}

	private fun setupWhatsNew() {
		val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
		val versionCode = pInfo.longVersionCode

		val changelogPath = findChangelogPath(versionCode)
		if (changelogPath != null) {
			btnWhatsNew.setConditionalVisibility(true)
			btnWhatsNew.setOnClickListener {
				showChangelogDialog(changelogPath)
			}
		} else {
			btnWhatsNew.setConditionalVisibility(false)
		}
	}

	private fun findChangelogPath(versionCode: Long): String? {
		val locale = activity.resources.configuration.locales[0]
		val language = locale.language
		val country = locale.country

		val fullLocale = if (country.isNotEmpty()) "$language-$country" else language
		val fullPath = "$fullLocale/changelogs/$versionCode.txt"

		if (assetExists(fullPath)) return fullPath

		val langPath = "$language/changelogs/$versionCode.txt"
		if (assetExists(langPath)) return langPath

		val defaultPath = "en-US/changelogs/$versionCode.txt"
		if (assetExists(defaultPath)) return defaultPath

		return null
	}

	private fun assetExists(path: String): Boolean {
		return try {
			activity.assets.open(path).use { true }
		} catch (_: Exception) {
			false
		}
	}

	private fun showChangelogDialog(path: String) {
		val rawContent = activity.assets.open(path).bufferedReader().use { it.readText() }
		val points = rawContent.lines()
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.map { it.removePrefix("-").removePrefix("*").trim() }

		val dialogView = activity.layoutInflater.inflate(R.layout.dialog_whats_new, null)
		val container = dialogView.findViewById<LinearLayout>(R.id.changelogPointsContainer)

		val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
		val versionName = pInfo.versionName
		dialogView.findViewById<TextView>(R.id.tvChangelogTitle).text =
			activity.getString(R.string.whats_new_version_format, versionName)

		points.forEach { pointText ->
			val pointView =
				activity.layoutInflater.inflate(R.layout.item_changelog_point, container, false)
			pointView.findViewById<TextView>(R.id.tvPointText).text = pointText
			container.addView(pointView)
		}

		com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
			.setView(dialogView)
			.setPositiveButton(R.string.close, null)
			.show()
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
