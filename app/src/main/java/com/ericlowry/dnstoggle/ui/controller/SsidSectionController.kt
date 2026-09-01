package com.ericlowry.dnstoggle.ui.controller

import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
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
import com.ericlowry.dnstoggle.util.setConditionalVisibility
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
	private lateinit var layoutWatchdogSubset: View
	private lateinit var ssidsAdapter: SsidsAdapter

	fun initialize(
		btnSsidInfo: ImageButton,
		addSsidButton: ImageButton,
		tvWifiProfilesTitle: TextView,
		permissionNoticeText: TextView,
		btnGrantPermission: Button,
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
		tvConnectivityWatchdogTargetsValue: TextView,
		layoutWatchdogSubset: View
	) {
		this.btnSsidInfo = btnSsidInfo
		this.addSsidButton = addSsidButton
		this.tvWifiProfilesTitle = tvWifiProfilesTitle
		this.permissionNoticeText = permissionNoticeText
		this.btnGrantPermission = btnGrantPermission
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
		this.layoutWatchdogSubset = layoutWatchdogSubset

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
			val container = activity.findViewById<ViewGroup>(R.id.contentWrapper)
			layoutWatchdogSubset.setConditionalVisibility(enabled, container)
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
		val container = activity.findViewById<ViewGroup>(R.id.contentWrapper)
		val isEmpty = profiles.isNullOrEmpty()
		val hasPermission = PermissionHelper.hasSsidPermissions(activity)

		val showList = !isEmpty && hasPermission
		val wasShowing = ssidListContainer.isVisible

		if (isEmpty) {
			ssidListContainer.itemAnimator = null
			ssidsAdapter.submitList(emptyList())
		} else {
			ssidListContainer.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
			val items = profiles.sortedByDescending { it.isAutoDetected }
			ssidsAdapter.submitList(items)
		}

		if (wasShowing != showList) {
			androidx.transition.TransitionManager.beginDelayedTransition(container)
		}

		dividerSsidSettings.setConditionalVisibility(showList, null)
		ssidListContainer.setConditionalVisibility(showList, null)
	}

	fun showAddSsidDialog(existingProfile: NetworkProfile? = null) {
		val suggestedSsid =
			if (existingProfile == null) NetworkUtils.getCurrentWifiSsid(activity) else null
		SsidDialogHelper.showAddSsidDialog(
			activity = activity,
			existingProfile = existingProfile,
			suggestedSsid = suggestedSsid,
			globalDefaultHostname = viewModel.getGlobalPreferredHostname(),
			hostnames = viewModel.dnsHostnames.value ?: emptyList(),
			enableStrictOff = viewModel.enableStrictOffOption.value ?: false,
			defaultOffMode = viewModel.defaultOffMode.value ?: Constants.DNS_MODE_OPPORTUNISTIC
		) { ssid, isEnabled, targetHostname, targetMode ->
			viewModel.saveNetworkProfile(
				existingProfile?.ssid,
				ssid,
				isEnabled,
				targetHostname,
				targetMode
			)
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
		val container = activity.findViewById<ViewGroup>(R.id.contentWrapper)

		permissionNoticeText.setConditionalVisibility(!hasPermission, container)
		btnGrantPermission.setConditionalVisibility(!hasPermission, container)

		addSsidButton.setConditionalVisibility(hasPermission, container)
		rowAutoSaveState.setConditionalVisibility(hasPermission, container)
		rowAutoSaveHost.setConditionalVisibility(hasPermission, container)
		rowConnectivityWatchdogToggle.setConditionalVisibility(hasPermission, container)

		val isEmpty = viewModel.networkProfiles.value.isNullOrEmpty()
		val showList = hasPermission && !isEmpty

		dividerSsidSettings.setConditionalVisibility(showList, container)
		ssidListContainer.setConditionalVisibility(showList, container)

		if (hasPermission) {
			val watchdogEnabled = viewModel.connectivityWatchdogEnabled.value ?: false
			layoutWatchdogSubset.setConditionalVisibility(watchdogEnabled, container)
		} else {
			layoutWatchdogSubset.setConditionalVisibility(false, container)
		}
	}
}
