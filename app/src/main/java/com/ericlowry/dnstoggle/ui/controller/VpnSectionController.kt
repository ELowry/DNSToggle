package com.ericlowry.dnstoggle.ui.controller

import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.ui.MainPermissionHandler
import com.ericlowry.dnstoggle.ui.dialog.DnsDialogHelper
import com.ericlowry.dnstoggle.util.PermissionHelper
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

		viewModel.vpnDnsHostname.observe(activity) { hostname ->
			tvVpnDnsValue.text = if (hostname == null) {
				activity.getString(R.string.automatic_off)
			} else {
				viewModel.dnsHostnames.value
					?.find { it.hostname == hostname }
					?.getDisplayName() ?: hostname
			}
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

	private fun setupVpnSettings() {
		rowVpnOverrideToggle.setOnClickListener { switchVpnOverride.toggle() }
		switchVpnOverride.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setVpnOverrideEnabled(isChecked)
		}

		rowVpnDns.setOnClickListener {
			val hostnames = viewModel.dnsHostnames.value ?: emptyList()
			if (hostnames.isEmpty()) return@setOnClickListener

			if (hostnames.size == 1) {
				val hostname = hostnames.first().hostname
				val current = viewModel.vpnDnsHostname.value
				val newValue = if (current == hostname) null else hostname
				viewModel.setVpnDnsHostname(newValue)
				return@setOnClickListener
			}

			DnsDialogHelper.showVpnDnsSelectionDialog(
				activity,
				hostnames,
				viewModel.vpnDnsHostname.value
			) { hostname ->
				viewModel.setVpnDnsHostname(hostname)
			}
		}

		btnGrantVpnPermission.setOnClickListener {
			permissionHandler.checkNotificationPermission()
		}

		btnVpnInfo.setOnClickListener { onShowWifiMonitoringInfoDialog() }
	}

	fun updateUiState(hasPermission: Boolean) {
		if (hasPermission) {
			vpnPermissionNoticeText.visibility = View.GONE
			btnGrantVpnPermission.visibility = View.GONE
			switchVpnOverride.isEnabled = true
			rowVpnDns.isEnabled = viewModel.vpnOverrideEnabled.value == true
			rowVpnDns.alpha = if (viewModel.vpnOverrideEnabled.value == true) 1.0f else 0.5f
		} else {
			vpnPermissionNoticeText.visibility = View.VISIBLE
			btnGrantVpnPermission.visibility = View.VISIBLE
			switchVpnOverride.isEnabled = false
			rowVpnDns.isEnabled = false
			rowVpnDns.alpha = 0.5f
		}
	}
}
