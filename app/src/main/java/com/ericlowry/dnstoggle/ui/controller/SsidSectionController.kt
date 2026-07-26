package com.ericlowry.dnstoggle.ui.controller

import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.data.NetworkProfile
import com.ericlowry.dnstoggle.ui.MainPermissionHandler
import com.ericlowry.dnstoggle.ui.adapter.SsidColors
import com.ericlowry.dnstoggle.ui.adapter.SsidTouchHelperCallback
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
	private lateinit var tvWifiProfilesTitle: TextView
	private lateinit var permissionNoticeText: TextView
	private lateinit var btnGrantPermission: Button
	private lateinit var dividerSsidList: View
	private lateinit var ssidListContainer: RecyclerView
	private lateinit var dividerSsidSettings: View
	private lateinit var rowAutoSaveState: View
	private lateinit var switchAutoSaveState: MaterialSwitch
	private lateinit var rowAutoSaveHost: View
	private lateinit var switchAutoSaveHost: MaterialSwitch
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
		tvWifiProfilesTitle: TextView,
		permissionNoticeText: TextView,
		btnGrantPermission: Button,
		dividerSsidList: View,
		ssidListContainer: RecyclerView,
		dividerSsidSettings: View,
		rowAutoSaveState: View,
		switchAutoSaveState: MaterialSwitch,
		rowAutoSaveHost: View,
		switchAutoSaveHost: MaterialSwitch,
		rowConnectivityWatchdogToggle: View,
		switchConnectivityWatchdog: MaterialSwitch,
		rowConnectivityWatchdogDebounce: View,
		tvConnectivityWatchdogDebounceValue: TextView,
		rowConnectivityWatchdogTargets: View,
		tvConnectivityWatchdogTargetsValue: TextView
	) {
		this.btnSsidInfo = btnSsidInfo
		this.addSsidButton = addSsidButton
		this.tvWifiProfilesTitle = tvWifiProfilesTitle
		this.permissionNoticeText = permissionNoticeText
		this.btnGrantPermission = btnGrantPermission
		this.dividerSsidList = dividerSsidList
		this.ssidListContainer = ssidListContainer
		this.dividerSsidSettings = dividerSsidSettings
		this.rowAutoSaveState = rowAutoSaveState
		this.switchAutoSaveState = switchAutoSaveState
		this.rowAutoSaveHost = rowAutoSaveHost
		this.switchAutoSaveHost = switchAutoSaveHost
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
		viewModel.networkProfiles.observe(activity) { profiles ->
			refreshSsidListView(profiles)
		}

		viewModel.dnsHostnames.observe(activity) { hostnames ->
			ssidsAdapter.updateHostnames(hostnames)
		}

		viewModel.currentSsid.observe(activity) { ssid ->
			ssidsAdapter.updateActiveSsid(ssid)
		}

		viewModel.autoSaveStateEnabled.observe(activity) { enabled ->
			switchAutoSaveState.isChecked = enabled
		}

		viewModel.autoSaveHostEnabled.observe(activity) { enabled ->
			switchAutoSaveHost.isChecked = enabled
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
			onToggleClick = { profile ->
				viewModel.upsertNetworkProfile(
					ssid = profile.ssid,
					isEnabled = !profile.isEnabled,
					targetHostname = profile.targetHostname,
					isAutoDetected = profile.isAutoDetected
				)
			},
			onEditClick = { profile -> showAddSsidDialog(profile) },
			onDeleteClick = { profile -> showDeleteConfirmDialog(profile.ssid) },
			onConfirmClick = { profile ->
				viewModel.promoteSsidToPermanent(profile.ssid)
				Toast.makeText(activity, R.string.ssid_saved, Toast.LENGTH_SHORT).show()
			},
			colors = colors
		)
		ssidListContainer.adapter = ssidsAdapter

		val itemTouchHelper = ItemTouchHelper(SsidTouchHelperCallback(ssidsAdapter, viewModel))
		itemTouchHelper.attachToRecyclerView(ssidListContainer)
	}

	private fun setupAutoSettings() {
		rowAutoSaveState.setOnClickListener { switchAutoSaveState.toggle() }
		switchAutoSaveState.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setAutoSaveState(isChecked)
			if (isChecked) {
				permissionHandler.checkSsidPermissions(requestIfNotGranted = true)
			}
		}

		rowAutoSaveHost.setOnClickListener { switchAutoSaveHost.toggle() }
		switchAutoSaveHost.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setAutoSaveHost(isChecked)
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

	private fun refreshSsidListView(profiles: List<NetworkProfile>?) {
		if (profiles.isNullOrEmpty()) {
			dividerSsidList.visibility = View.GONE
			dividerSsidSettings.visibility = View.GONE
			ssidsAdapter.submitList(emptyList())
			return
		}

		dividerSsidList.visibility = View.VISIBLE
		dividerSsidSettings.visibility = View.VISIBLE

		val items = profiles.sortedByDescending { it.isAutoDetected }
		ssidsAdapter.submitList(items)
	}

	fun showAddSsidDialog(existingProfile: NetworkProfile? = null) {
		val suggestedSsid =
			if (existingProfile == null) NetworkUtils.getCurrentWifiSsid(activity) else null
		SsidDialogHelper.showAddSsidDialog(
			activity = activity,
			existingProfile = existingProfile,
			suggestedSsid = suggestedSsid,
			globalDefaultHostname = viewModel.getGlobalPreferredHostname(),
			hostnames = viewModel.dnsHostnames.value ?: emptyList()
		) { ssid, isEnabled, targetHostname ->
			viewModel.saveNetworkProfile(existingProfile?.ssid, ssid, isEnabled, targetHostname)
		}
	}

	private fun showDeleteConfirmDialog(ssidToDelete: String) {
		CommonDialogHelper.showDeleteConfirmation(activity, R.string.delete_ssid_confirm) {
			viewModel.removeNetworkProfile(ssidToDelete)
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

			rowAutoSaveState.isEnabled = true
			rowAutoSaveState.alpha = 1.0f
			switchAutoSaveState.isEnabled = true

			rowAutoSaveHost.isEnabled = true
			rowAutoSaveHost.alpha = 1.0f
			switchAutoSaveHost.isEnabled = true

			ssidListContainer.alpha = 1.0f

			val watchdogEnabled = viewModel.connectivityWatchdogEnabled.value ?: false
			rowConnectivityWatchdogToggle.isEnabled = true
			rowConnectivityWatchdogToggle.alpha = 1.0f
			switchConnectivityWatchdog.isEnabled = true

			rowConnectivityWatchdogDebounce.isEnabled = watchdogEnabled
			rowConnectivityWatchdogDebounce.alpha = if (watchdogEnabled) 1.0f else 0.5f
			rowConnectivityWatchdogTargets.isEnabled = watchdogEnabled
			rowConnectivityWatchdogTargets.alpha = if (watchdogEnabled) 1.0f else 0.5f
		} else {
			permissionNoticeText.visibility = View.VISIBLE
			btnGrantPermission.visibility = View.VISIBLE
			addSsidButton.isEnabled = false

			rowAutoSaveState.isEnabled = false
			rowAutoSaveState.alpha = 0.5f
			switchAutoSaveState.isEnabled = false

			rowAutoSaveHost.isEnabled = false
			rowAutoSaveHost.alpha = 0.5f
			switchAutoSaveHost.isEnabled = false

			ssidListContainer.alpha = 0.5f

			rowConnectivityWatchdogToggle.isEnabled = false
			rowConnectivityWatchdogToggle.alpha = 0.5f
			switchConnectivityWatchdog.isEnabled = false

			rowConnectivityWatchdogDebounce.isEnabled = false
			rowConnectivityWatchdogDebounce.alpha = 0.5f
			rowConnectivityWatchdogTargets.isEnabled = false
			rowConnectivityWatchdogTargets.alpha = 0.5f
		}
	}
}
