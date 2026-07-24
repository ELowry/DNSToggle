package com.ericlowry.dnstoggle.ui.controller

import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.data.SsidItem
import com.ericlowry.dnstoggle.ui.MainPermissionHandler
import com.ericlowry.dnstoggle.ui.adapter.SsidColors
import com.ericlowry.dnstoggle.ui.adapter.SsidsAdapter
import com.ericlowry.dnstoggle.ui.dialog.CommonDialogHelper
import com.ericlowry.dnstoggle.ui.dialog.SsidDialogHelper
import com.ericlowry.dnstoggle.util.NetworkUtils
import com.ericlowry.dnstoggle.util.PermissionHelper
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as MaterialR

class SsidSectionController(
	private val activity: AppCompatActivity,
	private val viewModel: DnsViewModel,
	private val permissionHandler: MainPermissionHandler,
	private val onRequestIgnoreBattery: () -> Unit
) {
	private lateinit var btnSsidInfo: ImageButton
	private lateinit var addSsidButton: ImageButton
	private lateinit var permissionNoticeText: TextView
	private lateinit var btnGrantPermission: Button
	private lateinit var dividerSsidList: View
	private lateinit var ssidListContainer: RecyclerView
	private lateinit var dividerSsidSettings: View
	private lateinit var rowAutoBlacklist: View
	private lateinit var switchAutoBlacklist: MaterialSwitch
	private lateinit var rowAutoWhitelist: View
	private lateinit var switchAutoWhitelist: MaterialSwitch
	private lateinit var rowConnectivityWatchdogToggle: View
	private lateinit var switchConnectivityWatchdog: MaterialSwitch
	private lateinit var rowConnectivityWatchdogDebounce: View
	private lateinit var tvConnectivityWatchdogDebounceValue: TextView
	private lateinit var rowConnectivityWatchdogTargets: View
	private lateinit var tvConnectivityWatchdogTargetsValue: TextView
	private lateinit var ssidsAdapter: SsidsAdapter

	fun initialize(
		btnSsidInfo: ImageButton,
		addSsidButton: ImageButton,
		permissionNoticeText: TextView,
		btnGrantPermission: Button,
		dividerSsidList: View,
		ssidListContainer: RecyclerView,
		dividerSsidSettings: View,
		rowAutoBlacklist: View,
		switchAutoBlacklist: MaterialSwitch,
		rowAutoWhitelist: View,
		switchAutoWhitelist: MaterialSwitch,
		rowConnectivityWatchdogToggle: View,
		switchConnectivityWatchdog: MaterialSwitch,
		rowConnectivityWatchdogDebounce: View,
		tvConnectivityWatchdogDebounceValue: TextView,
		rowConnectivityWatchdogTargets: View,
		tvConnectivityWatchdogTargetsValue: TextView
	) {
		this.btnSsidInfo = btnSsidInfo
		this.addSsidButton = addSsidButton
		this.permissionNoticeText = permissionNoticeText
		this.btnGrantPermission = btnGrantPermission
		this.dividerSsidList = dividerSsidList
		this.ssidListContainer = ssidListContainer
		this.dividerSsidSettings = dividerSsidSettings
		this.rowAutoBlacklist = rowAutoBlacklist
		this.switchAutoBlacklist = switchAutoBlacklist
		this.rowAutoWhitelist = rowAutoWhitelist
		this.switchAutoWhitelist = switchAutoWhitelist
		this.rowConnectivityWatchdogToggle = rowConnectivityWatchdogToggle
		this.switchConnectivityWatchdog = switchConnectivityWatchdog
		this.rowConnectivityWatchdogDebounce = rowConnectivityWatchdogDebounce
		this.tvConnectivityWatchdogDebounceValue = tvConnectivityWatchdogDebounceValue
		this.rowConnectivityWatchdogTargets = rowConnectivityWatchdogTargets
		this.tvConnectivityWatchdogTargetsValue = tvConnectivityWatchdogTargetsValue

		setupSsidsRecyclerView()
		setupAutoSettings()
		setupWatchdog()
		setupAddSsid()
	}

	fun observeViewModel() {
		viewModel.ssidBlacklist.observe(activity) { blacklist ->
			refreshSsidListView(blacklist)
		}

		viewModel.autoDetectedBlacklist.observe(activity) {
			refreshSsidListView(viewModel.ssidBlacklist.value ?: emptySet())
		}

		viewModel.currentSsid.observe(activity) { ssid ->
			ssidsAdapter.updateActiveSsid(ssid)
		}

		viewModel.autoBlacklistEnabled.observe(activity) { enabled ->
			switchAutoBlacklist.isChecked = enabled
		}

		viewModel.autoWhitelistEnabled.observe(activity) { enabled ->
			switchAutoWhitelist.isChecked = enabled
		}

		viewModel.connectivityWatchdogEnabled.observe(activity) { enabled ->
			switchConnectivityWatchdog.isChecked = enabled
			rowConnectivityWatchdogDebounce.isEnabled = enabled
			rowConnectivityWatchdogDebounce.alpha = if (enabled) 1.0f else 0.5f
			rowConnectivityWatchdogTargets.isEnabled = enabled
			rowConnectivityWatchdogTargets.alpha = if (enabled) 1.0f else 0.5f
		}

		viewModel.connectivityWatchdogDebounceSeconds.observe(activity) { seconds ->
			tvConnectivityWatchdogDebounceValue.text =
				activity.getString(R.string.connectivity_watchdog_debounce_seconds_format, seconds)
		}

		viewModel.connectivityWatchdogProbeTargets.observe(activity) { targets ->
			tvConnectivityWatchdogTargetsValue.text = targets
		}
	}

	private fun setupSsidsRecyclerView() {
		val colors = SsidColors(
			colorSurface = MaterialColors.getColor(
				activity,
				MaterialR.attr.colorSurface,
				0
			),
			colorSurfaceContainer = MaterialColors.getColor(
				activity,
				MaterialR.attr.colorSurfaceContainer,
				0
			),
			colorPrimary = MaterialColors.getColor(activity, android.R.attr.colorPrimary, 0),
			colorOutlineVariant = MaterialColors.getColor(
				activity,
				MaterialR.attr.colorOutlineVariant,
				0
			)
		)

		ssidsAdapter = SsidsAdapter(
			onEditClick = { ssid -> showAddSsidDialog(ssid) },
			onDeleteClick = { ssid -> showDeleteConfirmDialog(ssid) },
			onConfirmClick = { ssid ->
				viewModel.promoteSsidToPermanent(ssid)
				Toast.makeText(activity, R.string.ssid_saved, Toast.LENGTH_SHORT).show()
			},
			colors = colors
		)
		ssidListContainer.adapter = ssidsAdapter
	}

	private fun setupAutoSettings() {
		rowAutoBlacklist.setOnClickListener { switchAutoBlacklist.toggle() }
		switchAutoBlacklist.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setAutoBlacklist(isChecked)
			if (isChecked) {
				permissionHandler.checkSsidPermissions(requestIfNotGranted = true)
			}
		}

		rowAutoWhitelist.setOnClickListener { switchAutoWhitelist.toggle() }
		switchAutoWhitelist.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setAutoWhitelist(isChecked)
			if (isChecked) {
				permissionHandler.checkSsidPermissions(requestIfNotGranted = true)
			}
		}
	}

	private fun setupWatchdog() {
		rowConnectivityWatchdogToggle.setOnClickListener { switchConnectivityWatchdog.toggle() }
		switchConnectivityWatchdog.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setConnectivityWatchdogEnabled(isChecked)
			if (isChecked) {
				permissionHandler.checkSsidPermissions(requestIfNotGranted = true)
			}
		}

		rowConnectivityWatchdogDebounce.setOnClickListener {
			SsidDialogHelper.showConnectivityWatchdogDebounceDialog(
				activity,
				viewModel.connectivityWatchdogDebounceSeconds.value
					?: Constants.CONNECTIVITY_WATCHDOG_DEFAULT_DEBOUNCE_SECONDS,
				listOf(10, 15, 30, 60)
			) { seconds ->
				viewModel.setConnectivityWatchdogDebounceSeconds(seconds)
			}
		}

		rowConnectivityWatchdogTargets.setOnClickListener {
			CommonDialogHelper.showTextInputDialog(
				activity,
				R.string.connectivity_watchdog_targets_title,
				R.string.connectivity_watchdog_targets_hint,
				viewModel.connectivityWatchdogProbeTargets.value ?: "",
			) { targets ->
				viewModel.setConnectivityWatchdogProbeTargets(targets)
			}
		}
	}

	private fun setupAddSsid() {
		addSsidButton.setOnClickListener {
			if (PermissionHelper.hasSsidPermissions(activity)) {
				showAddSsidDialog()
			} else {
				permissionHandler.requestSsidPermissions()
			}
		}

		btnGrantPermission.setOnClickListener { permissionHandler.requestSsidPermissions() }
		btnSsidInfo.setOnClickListener { showWifiMonitoringInfoDialog() }
	}

	private fun refreshSsidListView(blacklist: Set<String>) {
		if (blacklist.isEmpty()) {
			dividerSsidList.visibility = View.GONE
			dividerSsidSettings.visibility = View.GONE
			ssidsAdapter.submitList(emptyList())
			return
		}

		dividerSsidList.visibility = View.VISIBLE
		dividerSsidSettings.visibility = View.VISIBLE

		val autoDetected = viewModel.autoDetectedBlacklist.value ?: emptySet()
		val items = blacklist.map { ssid ->
			SsidItem(ssid, isAutoDetected = autoDetected.contains(ssid))
		}.sortedWith { a, b ->
			when {
				a.isAutoDetected != b.isAutoDetected -> if (a.isAutoDetected) -1 else 1
				else -> a.ssid.lowercase().compareTo(b.ssid.lowercase())
			}
		}
		ssidsAdapter.submitList(items)
	}

	fun showAddSsidDialog(existingSsid: String? = null) {
		val suggestedSsid =
			if (existingSsid == null) NetworkUtils.getCurrentWifiSsid(activity) else null
		SsidDialogHelper.showAddSsidDialog(activity, existingSsid, suggestedSsid) { newSsidName ->
			if (existingSsid != null) {
				viewModel.updateSsidInBlacklist(existingSsid, newSsidName)
			} else {
				viewModel.addToBlacklist(newSsidName)
			}
		}
	}

	private fun showDeleteConfirmDialog(ssidToDelete: String) {
		CommonDialogHelper.showDeleteConfirmation(activity, R.string.delete_ssid_confirm) {
			viewModel.removeFromBlacklist(ssidToDelete)
		}
	}

	private fun showWifiMonitoringInfoDialog() {
		val powerManager = ContextCompat.getSystemService(activity, PowerManager::class.java)!!
		val isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(activity.packageName)
		SsidDialogHelper.showWifiMonitoringInfo(activity, isIgnoringBattery) {
			onRequestIgnoreBattery()
		}
	}

	fun updateUiState(hasPermission: Boolean) {
		if (hasPermission) {
			permissionNoticeText.visibility = View.GONE
			btnGrantPermission.visibility = View.GONE
			addSsidButton.isEnabled = true
			switchAutoBlacklist.isEnabled = true
			switchAutoWhitelist.isEnabled = true
			ssidListContainer.alpha = 1.0f

			val watchdogEnabled = viewModel.connectivityWatchdogEnabled.value ?: false
			switchConnectivityWatchdog.isEnabled = true
			rowConnectivityWatchdogToggle.alpha = 1.0f
			rowConnectivityWatchdogDebounce.isEnabled = watchdogEnabled
			rowConnectivityWatchdogDebounce.alpha = if (watchdogEnabled) 1.0f else 0.5f
			rowConnectivityWatchdogTargets.isEnabled = watchdogEnabled
			rowConnectivityWatchdogTargets.alpha = if (watchdogEnabled) 1.0f else 0.5f
		} else {
			permissionNoticeText.visibility = View.VISIBLE
			btnGrantPermission.visibility = View.VISIBLE
			addSsidButton.isEnabled = false
			switchAutoBlacklist.isEnabled = false
			switchAutoWhitelist.isEnabled = false
			ssidListContainer.alpha = 0.5f

			switchConnectivityWatchdog.isEnabled = false
			rowConnectivityWatchdogToggle.alpha = 0.5f
			rowConnectivityWatchdogDebounce.isEnabled = false
			rowConnectivityWatchdogDebounce.alpha = 0.5f
			rowConnectivityWatchdogTargets.isEnabled = false
			rowConnectivityWatchdogTargets.alpha = 0.5f
		}
	}
}
