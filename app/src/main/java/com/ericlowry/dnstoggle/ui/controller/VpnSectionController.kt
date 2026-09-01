package com.ericlowry.dnstoggle.ui.controller

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.ui.MainPermissionHandler
import com.ericlowry.dnstoggle.ui.dialog.DnsDialogHelper
import com.ericlowry.dnstoggle.util.PermissionHelper
import com.ericlowry.dnstoggle.util.setConditionalVisibility
import com.ericlowry.dnstoggle.util.setDimmedEnabled
import com.google.android.material.materialswitch.MaterialSwitch

class VpnSectionController(
	private val activity: AppCompatActivity,
	private val viewModel: DnsViewModel,
	private val permissionHandler: MainPermissionHandler,
	private val onShowWifiMonitoringInfoDialog: () -> Unit
) {
	private lateinit var btnVpnInfo: ImageButton
	private lateinit var rowVpnOverrideToggle: View
	private lateinit var switchVpnOverride: MaterialSwitch
	private lateinit var rowVpnDns: View
	private lateinit var tvVpnDnsValue: TextView
	private lateinit var vpnPermissionNoticeText: TextView
	private lateinit var btnGrantVpnPermission: Button

	fun initialize(
		btnVpnInfo: ImageButton,
		rowVpnOverrideToggle: View,
		switchVpnOverride: MaterialSwitch,
		rowVpnDns: View,
		tvVpnDnsValue: TextView,
		vpnPermissionNoticeText: TextView,
		btnGrantVpnPermission: Button
	) {
		this.btnVpnInfo = btnVpnInfo
		this.rowVpnOverrideToggle = rowVpnOverrideToggle
		this.switchVpnOverride = switchVpnOverride
		this.rowVpnDns = rowVpnDns
		this.tvVpnDnsValue = tvVpnDnsValue
		this.vpnPermissionNoticeText = vpnPermissionNoticeText
		this.btnGrantVpnPermission = btnGrantVpnPermission

		setupVpnSettings()
	}

	fun observeViewModel() {
		viewModel.vpnOverrideEnabled.observe(activity) { enabled ->
			switchVpnOverride.isChecked = enabled
			updateUiState(PermissionHelper.hasNotificationPermission(activity))
		}

		viewModel.vpnDnsMode.observe(activity) { _ ->
			updateVpnDnsLabel()
		}

		viewModel.vpnDnsHostname.observe(activity) { _ ->
			updateVpnDnsLabel()
		}

		viewModel.vpnHostnameRemovedWarning.observe(activity) { show ->
			if (show) {
				AlertDialog.Builder(activity)
					.setTitle(R.string.vpn_override_title)
					.setMessage(R.string.vpn_hostname_removed_warning)
					.setPositiveButton(R.string.ok) { _, _ ->
						viewModel.dismissVpnHostnameWarning()
					}
					.show()
			}
		}
	}

	fun updateUiState(hasPermission: Boolean) {
		val container = activity.findViewById<ViewGroup>(R.id.contentWrapper)

		vpnPermissionNoticeText.setConditionalVisibility(!hasPermission, container)
		btnGrantVpnPermission.setConditionalVisibility(!hasPermission, container)

		rowVpnOverrideToggle.setConditionalVisibility(hasPermission, container)
		rowVpnDns.setConditionalVisibility(hasPermission, container)

		if (hasPermission) {
			val vpnEnabled = viewModel.vpnOverrideEnabled.value == true
			rowVpnDns.setDimmedEnabled(vpnEnabled)
		}
	}

	private fun updateVpnDnsLabel() {
		val mode = viewModel.vpnDnsMode.value ?: Constants.DNS_MODE_OPPORTUNISTIC
		val hostname = viewModel.vpnDnsHostname.value

		tvVpnDnsValue.text = when (mode) {
			Constants.DNS_MODE_OPPORTUNISTIC -> activity.getString(R.string.off_automatic_label)
			Constants.DNS_MODE_OFF -> activity.getString(R.string.off_strict_label)
			else -> {
				viewModel.dnsHostnames.value
					?.find { it.hostname == hostname }
					?.getDisplayName() ?: hostname
				?: activity.getString(R.string.off_automatic_label)
			}
		}
	}

	private fun setupVpnSettings() {
		rowVpnOverrideToggle.setOnClickListener { switchVpnOverride.toggle() }
		switchVpnOverride.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setVpnOverrideEnabled(isChecked)
		}

		rowVpnDns.setOnClickListener {
			val hostnames = viewModel.dnsHostnames.value ?: emptyList()
			if (hostnames.isEmpty()) {
				return@setOnClickListener
			}

			if (hostnames.size == 1 && !(viewModel.enableStrictOffOption.value ?: false)) {
				val hostname = hostnames.first().hostname
				val current = viewModel.vpnDnsHostname.value
				val currentMode = viewModel.vpnDnsMode.value
				if (currentMode == Constants.DNS_MODE_HOSTNAME && current == hostname) {
					viewModel.setVpnDns(Constants.DNS_MODE_OPPORTUNISTIC, null)
				} else {
					viewModel.setVpnDns(Constants.DNS_MODE_HOSTNAME, hostname)
				}
				return@setOnClickListener
			}

			DnsDialogHelper.showVpnDnsSelectionDialog(
				activity,
				hostnames,
				viewModel.vpnDnsMode.value ?: Constants.DNS_MODE_OPPORTUNISTIC,
				viewModel.vpnDnsHostname.value,
				viewModel.enableStrictOffOption.value ?: false
			) { mode, hostname ->
				viewModel.setVpnDns(mode, hostname)
			}
		}

		btnGrantVpnPermission.setOnClickListener {
			permissionHandler.checkNotificationPermission()
		}

		btnVpnInfo.setOnClickListener { onShowWifiMonitoringInfoDialog() }
	}
}
