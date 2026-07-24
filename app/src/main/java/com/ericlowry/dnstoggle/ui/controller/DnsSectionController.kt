package com.ericlowry.dnstoggle.ui.controller

import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.ui.adapter.DnsTouchHelperCallback
import com.ericlowry.dnstoggle.ui.adapter.HostnameColors
import com.ericlowry.dnstoggle.ui.adapter.HostnamesAdapter
import com.ericlowry.dnstoggle.ui.dialog.CommonDialogHelper
import com.ericlowry.dnstoggle.ui.dialog.DnsDialogHelper
import com.ericlowry.dnstoggle.util.NetworkUtils
import com.ericlowry.dnstoggle.util.PermissionHelper
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as MaterialR

class DnsSectionController(
	private val activity: AppCompatActivity,
	private val viewModel: DnsViewModel,
	private val onShowInitialPermissionDialog: () -> Unit,
	private val onRequestTileUpdate: () -> Unit
) {
	private lateinit var dnsToggleSwitch: MaterialSwitch
	private lateinit var addHostnameButton: ImageButton
	private lateinit var dnsHostnameListContainer: RecyclerView
	private lateinit var rowDisableDnsTest: View
	private lateinit var switchDisableDnsTest: MaterialSwitch
	private lateinit var hostnamesAdapter: HostnamesAdapter

	fun initialize(
		dnsToggleSwitch: MaterialSwitch,
		addHostnameButton: ImageButton,
		dnsHostnameListContainer: RecyclerView,
		rowDisableDnsTest: View,
		switchDisableDnsTest: MaterialSwitch
	) {
		this.dnsToggleSwitch = dnsToggleSwitch
		this.addHostnameButton = addHostnameButton
		this.dnsHostnameListContainer = dnsHostnameListContainer
		this.rowDisableDnsTest = rowDisableDnsTest
		this.switchDisableDnsTest = switchDisableDnsTest

		setupDnsToggle()
		setupAddHostname()
		setupHostnamesRecyclerView()
		setupDisableDnsTest()
	}

	fun observeViewModel() {
		viewModel.privateDnsMode.observe(activity) { updateHostnamesMetadata() }
		viewModel.privateDnsSpecifier.observe(activity) { updateHostnamesMetadata() }
		viewModel.dnsReachability.observe(activity) { updateHostnamesMetadata() }

		viewModel.dnsHostnames.observe(activity) { hostnames ->
			hostnamesAdapter.submitList(hostnames)
		}

		viewModel.disableDnsTest.observe(activity) { disabled ->
			switchDisableDnsTest.isChecked = disabled
		}
	}

	private fun updateHostnamesMetadata() {
		val mode = viewModel.privateDnsMode.value
		val specifier = viewModel.privateDnsSpecifier.value
		val reachability = viewModel.dnsReachability.value
		val isEnabled = (mode == Constants.DNS_MODE_HOSTNAME)

		dnsToggleSwitch.isChecked = isEnabled
		hostnamesAdapter.updateMetadata(reachability, specifier, isEnabled)
	}

	private fun setupDnsToggle() {
		dnsToggleSwitch.setOnClickListener {
			val isChecked = dnsToggleSwitch.isChecked

			if (PermissionHelper.hasSecureSettingsPermission(activity)) {
				viewModel.togglePrivateDns(isChecked)
				onRequestTileUpdate()
			} else {
				dnsToggleSwitch.isChecked = !isChecked
				onShowInitialPermissionDialog()
			}
		}
	}

	private fun setupAddHostname() {
		addHostnameButton.setOnClickListener {
			showAddHostnameDialog()
		}
	}

	private fun setupHostnamesRecyclerView() {
		val colors = HostnameColors(
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
			colorSecondaryContainer = MaterialColors.getColor(
				activity,
				MaterialR.attr.colorSecondaryContainer,
				0
			),
			colorPrimary = MaterialColors.getColor(activity, android.R.attr.colorPrimary, 0),
			colorOutlineVariant = MaterialColors.getColor(
				activity,
				MaterialR.attr.colorOutlineVariant,
				0
			),
			textColorSecondary = MaterialColors.getColor(
				activity,
				android.R.attr.textColorSecondary,
				0
			),
			warningColor = MaterialColors.getColor(activity, R.attr.warning_color, 0)
		)

		hostnamesAdapter = HostnamesAdapter(
			onEditClick = { hostname -> showAddHostnameDialog(hostname) },
			onDeleteClick = { hostname -> showDeleteHostnameConfirmDialog(hostname) },
			onItemClick = { hostname -> viewModel.togglePrivateDns(true, hostname) },
			onAddInPlaceClick = { hostname ->
				viewModel.addHostname(hostname)
				Toast.makeText(activity, R.string.hostname_saved, Toast.LENGTH_SHORT).show()
			},
			colors = colors
		)
		dnsHostnameListContainer.adapter = hostnamesAdapter

		val itemTouchHelper = ItemTouchHelper(DnsTouchHelperCallback(hostnamesAdapter, viewModel))
		itemTouchHelper.attachToRecyclerView(dnsHostnameListContainer)
	}

	private fun setupDisableDnsTest() {
		rowDisableDnsTest.setOnClickListener { switchDisableDnsTest.toggle() }
		switchDisableDnsTest.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setDisableDnsTest(isChecked)
		}
	}

	fun showAddHostnameDialog(
		existingHostname: String? = null,
		enableAfterSave: Boolean = false
	) {
		val existingEntry = existingHostname?.let { host ->
			viewModel.dnsHostnames.value?.find { it.hostname == host }
		}

		DnsDialogHelper.showAddHostnameDialog(
			activity,
			existingHostname,
			existingEntry?.label
		) { newHostname, newLabel ->
			if (NetworkUtils.isValidDnsHostname(newHostname)) {
				if (existingHostname != null) {
					viewModel.updateHostname(existingHostname, newHostname, newLabel)
				} else {
					viewModel.addHostname(newHostname, newLabel)
				}

				if (enableAfterSave) {
					viewModel.togglePrivateDns(true, newHostname)
				}
			} else {
				Toast.makeText(activity, R.string.error_invalid_dns_host, Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun showDeleteHostnameConfirmDialog(hostname: String) {
		CommonDialogHelper.showDeleteConfirmation(activity, R.string.delete_hostname_confirm) {
			viewModel.removeHostname(hostname)
		}
	}
}
