package com.ericlowry.dnstoggle

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "MainActivity"
		const val EXTRA_FOCUS_DNS_INPUT = "focus_dns_input"
	}

	private lateinit var dnsViewModel: DnsViewModel

	private lateinit var privateDnsLabel: TextView
	private lateinit var dnsToggleSwitch: MaterialSwitch
	private lateinit var progressRootAction: com.google.android.material.progressindicator.CircularProgressIndicator
	private lateinit var dnsHostnameListContainer: LinearLayout
	private lateinit var addHostnameButton: ImageButton

	private lateinit var switchAutoBlacklist: MaterialSwitch
	private lateinit var switchAutoWhitelist: MaterialSwitch
	private lateinit var switchDisableDnsTest: MaterialSwitch
	private lateinit var switchShowToast: MaterialSwitch
	private lateinit var switchHideLauncher: MaterialSwitch
	private lateinit var permissionNoticeText: TextView
	private lateinit var btnGrantPermission: Button
	private lateinit var ssidListContainer: LinearLayout
	private lateinit var addSsidButton: ImageButton
	private lateinit var btnSsidInfo: ImageButton
	private lateinit var dividerSsidList: View

	private lateinit var cardMainPermission: com.google.android.material.card.MaterialCardView
	private lateinit var btnFixMainPermission: Button

	private var permissionDialog: AlertDialog? = null
	private var isRedirectedFromTile = false

	private val foregroundPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions(),
	) { results ->
		val allGranted = results.entries.all { it.value }
		if (allGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			showBackgroundLocationRationale()
		} else {
			updateSsidUiState(allGranted)
			(application as DnsToggleApplication).updateWifiMonitoringRegistration()
			if (allGranted) checkNotificationPermission()
		}
	}

	private val backgroundPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { _ ->
		checkSsidPermissions(requestIfNotGranted = false)
		(application as DnsToggleApplication).updateWifiMonitoringRegistration()
	}

	private val notificationPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { _ ->
		// Choice recorded by rationale or system, service will handle permission check
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		dnsViewModel = ViewModelProvider(this)[DnsViewModel::class.java]

		setupWindowInsets()
		setupToolbar()
		initializeViews()
		observeViewModel()

		setupUserInteractions()
		updateMainPermissionUiState()
		updateSsidUiState(hasSsidPermissions())
		handleIntentExtras(intent)
	}

	override fun onResume() {
		super.onResume()
		updateMainPermissionUiState()
		updateSsidUiState(hasSsidPermissions())
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntentExtras(intent)
	}

	private fun handleIntentExtras(intent: Intent?) {
		if (intent?.getBooleanExtra(
				"show_permission_dialog",
				false
			) == true && !hasSecureSettingsPermission()
		) {
			lifecycleScope.launch {
				RootUtils.grantSecureSettingsPermission(packageName)
				updateMainPermissionUiState()
				if (!hasSecureSettingsPermission()) {
					showInitialPermissionDialog()
				}
			}
		}

		if (intent?.getBooleanExtra(EXTRA_FOCUS_DNS_INPUT, false) == true) {
			isRedirectedFromTile = true
			showAddHostnameDialog()
		}
	}

	private fun setupWindowInsets() {
		val mainView = findViewById<View>(R.id.main)
		ViewCompat.setOnApplyWindowInsetsListener(mainView) { view, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			view.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
			insets
		}
	}

	private fun setupToolbar() {
		val toolbar =
			findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
		setSupportActionBar(toolbar)
	}

	private fun initializeViews() {
		privateDnsLabel = findViewById(R.id.tvToggleLabel)
		dnsToggleSwitch = findViewById(R.id.switchPrivateDns)
		progressRootAction = findViewById(R.id.progressRootAction)
		dnsHostnameListContainer = findViewById(R.id.dnsHostnameListContainer)
		addHostnameButton = findViewById(R.id.btnAddHostname)

		switchAutoBlacklist = findViewById(R.id.switchAutoBlacklist)
		switchAutoWhitelist = findViewById(R.id.switchAutoWhitelist)
		switchDisableDnsTest = findViewById(R.id.switchDisableDnsTest)
		switchShowToast = findViewById(R.id.switchShowToast)
		switchHideLauncher = findViewById(R.id.switchHideLauncher)
		permissionNoticeText = findViewById(R.id.tvPermissionNotice)
		btnGrantPermission = findViewById(R.id.btnGrantPermission)
		ssidListContainer = findViewById(R.id.ssidListContainer)
		addSsidButton = findViewById(R.id.btnAddSsid)
		btnSsidInfo = findViewById(R.id.btnSsidInfo)
		dividerSsidList = findViewById(R.id.dividerSsidList)

		cardMainPermission = findViewById(R.id.cardMainPermission)
		btnFixMainPermission = findViewById(R.id.btnFixMainPermission)

		privateDnsLabel.text = getString(R.string.private_dns)
	}

	private fun observeViewModel() {
		dnsViewModel.privateDnsMode.observe(this) { mode ->
			val isEnabled = (mode == "hostname")
			dnsToggleSwitch.isChecked = isEnabled
		}

		dnsViewModel.privateDnsSpecifier.observe(this) { _ ->
			// Update hostname list to reflect active one
			refreshHostnameListView(dnsViewModel.dnsHostnames.value ?: emptySet())
		}

		dnsViewModel.dnsHostnames.observe(this) { hostnames ->
			refreshHostnameListView(hostnames)
		}

		dnsViewModel.ssidBlacklist.observe(this) { blacklist ->
			refreshSsidListView(blacklist)
		}

		dnsViewModel.autoBlacklistEnabled.observe(this) { enabled ->
			switchAutoBlacklist.isChecked = enabled
		}

		dnsViewModel.autoWhitelistEnabled.observe(this) { enabled ->
			switchAutoWhitelist.isChecked = enabled
		}

		dnsViewModel.disableDnsTest.observe(this) { disabled ->
			switchDisableDnsTest.isChecked = disabled
		}

		dnsViewModel.showToastEnabled.observe(this) { enabled ->
			switchShowToast.isChecked = enabled
		}

		dnsViewModel.hideLauncherIcon.observe(this) { isHidden ->
			switchHideLauncher.isChecked = isHidden
			updateLauncherComponentState(isHidden)
		}

		dnsViewModel.dnsReachability.observe(this) { _ ->
			refreshHostnameListView(dnsViewModel.dnsHostnames.value ?: emptySet())
		}

		dnsViewModel.hasPermissionError.observe(this) { hasError ->
			if (hasError) {
				showInitialPermissionDialog()
			}
		}

		dnsViewModel.isKeyInvalidated.observe(this) { invalidated ->
			if (invalidated) {
				showKeyInvalidatedDialog()
			}
		}

		updateToolbarTitle()
	}

	private fun setupUserInteractions() {
		dnsToggleSwitch.setOnClickListener {
			val isChecked = dnsToggleSwitch.isChecked

			if (hasSecureSettingsPermission()) {
				dnsViewModel.togglePrivateDns(isChecked)
				requestTileUpdate()
			} else {
				// Attempt root grant again
				setLoadingState(true)
				lifecycleScope.launch {
					RootUtils.grantSecureSettingsPermission(packageName)
					setLoadingState(false)
					updateMainPermissionUiState()

					if (hasSecureSettingsPermission()) {
						dnsViewModel.togglePrivateDns(isChecked)
						requestTileUpdate()
					} else {
						// Revert UI and show manual instructions
						dnsToggleSwitch.isChecked = !isChecked
						showInitialPermissionDialog()
					}
				}
			}
		}

		addHostnameButton.setOnClickListener {
			showAddHostnameDialog()
		}

		switchAutoBlacklist.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setAutoBlacklist(isChecked)
			if (isChecked) {
				checkSsidPermissions(requestIfNotGranted = true)
			}
		}

		switchAutoWhitelist.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setAutoWhitelist(isChecked)
			if (isChecked) {
				checkSsidPermissions(requestIfNotGranted = true)
			}
		}

		switchDisableDnsTest.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setDisableDnsTest(isChecked)
		}

		switchShowToast.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setShowToast(isChecked)
		}

		switchHideLauncher.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setHideLauncherIcon(isChecked)
		}

		addSsidButton.setOnClickListener {
			if (hasSsidPermissions()) {
				showAddSsidDialog()
			} else {
				requestSsidPermissions()
			}
		}
		btnGrantPermission.setOnClickListener { requestSsidPermissions() }
		btnSsidInfo.setOnClickListener { showWifiMonitoringInfoDialog() }

		btnFixMainPermission.setOnClickListener {
			if (hasSecureSettingsPermission()) {
				updateMainPermissionUiState()
			} else {
				setLoadingState(true)
				lifecycleScope.launch {
					val success = RootUtils.grantSecureSettingsPermission(packageName)
					setLoadingState(false)
					updateMainPermissionUiState()

					if (!hasSecureSettingsPermission()) {
						showInitialPermissionDialog()
					} else if (success) {
						Toast.makeText(
							this@MainActivity,
							getString(R.string.permission_granted),
							Toast.LENGTH_SHORT
						).show()
					}
				}
			}
		}
	}

	private fun hasSecureSettingsPermission(): Boolean {
		return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
	}

	private fun updateMainPermissionUiState() {
		if (hasSecureSettingsPermission()) {
			cardMainPermission.visibility = View.GONE
		} else {
			cardMainPermission.visibility = View.VISIBLE
		}
	}

	private fun hasSsidPermissions(): Boolean {
		val requiredPermissions = mutableListOf<String>()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
			requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
		} else {
			requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
			requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
		}

		return requiredPermissions.all { permission ->
			ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
		}
	}

	private fun checkSsidPermissions(requestIfNotGranted: Boolean = false) {
		val allGranted = hasSsidPermissions()
		updateSsidUiState(allGranted)
		if (!allGranted && requestIfNotGranted) {
			requestSsidPermissions()
		} else if (allGranted) {
			checkNotificationPermission()
		}
	}

	private fun requestSsidPermissions() {
		val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			arrayOf(
				Manifest.permission.NEARBY_WIFI_DEVICES,
				Manifest.permission.ACCESS_FINE_LOCATION
			)
		} else {
			arrayOf(
				Manifest.permission.ACCESS_FINE_LOCATION,
				Manifest.permission.ACCESS_COARSE_LOCATION
			)
		}

		val prefs = (application as DnsToggleApplication).getPrefs()
		val hasRequestedBefore = prefs.getBoolean("has_requested_ssid_perms", false)

		if (hasRequestedBefore) {
			val isPermanentlyDenied = permissions.any { perm ->
				ContextCompat.checkSelfPermission(
					this,
					perm
				) != PackageManager.PERMISSION_GRANTED &&
						!shouldShowRequestPermissionRationale(perm)
			}

			if (isPermanentlyDenied) {
				MaterialAlertDialogBuilder(this)
					.setTitle(getString(R.string.permission_required))
					.setMessage(getString(R.string.permissions_permanently_denied))
					.setPositiveButton(getString(R.string.open_settings)) { _, _ -> openAppSettings() }
					.setNegativeButton(getString(R.string.cancel), null)
					.show()
				return
			}
		}

		prefs.edit { putBoolean("has_requested_ssid_perms", true) }
		foregroundPermissionLauncher.launch(permissions)
	}

	private fun showBackgroundLocationRationale() {
		MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.permission_required))
			.setMessage(getString(R.string.background_location_explanation))
			.setPositiveButton(getString(R.string.ok)) { _, _ ->
				requestBackgroundLocationPermission()
			}
			.setNegativeButton(getString(R.string.cancel)) { _, _ ->
				updateSsidUiState(hasSsidPermissions())
				(application as DnsToggleApplication).updateWifiMonitoringRegistration()
			}
			.show()
	}

	private fun requestBackgroundLocationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
		}
	}

	private fun checkNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			val sharedPreferences = (application as DnsToggleApplication).getPrefs()
			val alreadyHandled = sharedPreferences.getBoolean("notif_permission_handled", false)
			val isGranted = ContextCompat.checkSelfPermission(
				this,
				Manifest.permission.POST_NOTIFICATIONS
			) == PackageManager.PERMISSION_GRANTED

			if (!isGranted && !alreadyHandled) {
				showNotificationPermissionRationale()
			}
		}
	}

	private fun showNotificationPermissionRationale() {
		val sharedPreferences = (application as DnsToggleApplication).getPrefs()

		MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.permission_required))
			.setMessage(getString(R.string.notification_permission_explanation))
			.setPositiveButton(getString(R.string.ok)) { _, _ ->
				sharedPreferences.edit { putBoolean("notif_permission_handled", true) }
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
				}
			}
			.setNegativeButton(getString(R.string.cancel)) { _, _ ->
				sharedPreferences.edit { putBoolean("notif_permission_handled", true) }
			}
			.setCancelable(false)
			.show()
	}

	private fun updateSsidUiState(hasPermission: Boolean) {
		if (hasPermission) {
			permissionNoticeText.visibility = View.GONE
			btnGrantPermission.visibility = View.GONE
			addSsidButton.isEnabled = true
			switchAutoBlacklist.isEnabled = true
			switchAutoWhitelist.isEnabled = true
			ssidListContainer.alpha = 1.0f
		} else {
			permissionNoticeText.visibility = View.VISIBLE
			btnGrantPermission.visibility = View.VISIBLE
			addSsidButton.isEnabled = false
			switchAutoBlacklist.isEnabled = false
			switchAutoWhitelist.isEnabled = false
			ssidListContainer.alpha = 0.5f
		}
	}

	private fun refreshHostnameListView(hostnames: Set<String>) {
		dnsHostnameListContainer.removeAllViews()

		val sortedList = hostnames.toMutableList()
		sortedList.sortWith(String.CASE_INSENSITIVE_ORDER)

		val layoutInflater = LayoutInflater.from(this)
		val currentSpecifier = dnsViewModel.privateDnsSpecifier.value
		val reachabilityMap = dnsViewModel.dnsReachability.value ?: emptyMap()

		sortedList.forEach { hostname ->
			val itemView = layoutInflater.inflate(
				R.layout.item_hostname,
				dnsHostnameListContainer,
				false
			) as com.google.android.material.card.MaterialCardView
			val tvHostname = itemView.findViewById<TextView>(R.id.tvHostname)
			val tvStatus = itemView.findViewById<TextView>(R.id.tvStatus)
			val btnDelete = itemView.findViewById<View>(R.id.btnDeleteHostname)

			tvHostname.text = hostname

			val reachability = reachabilityMap[hostname] ?: DnsViewModel.ReachabilityState.IDLE
			val isActive = (hostname == currentSpecifier && dnsToggleSwitch.isChecked)

			if (isActive) {
				itemView.strokeColor = getThemeColor(android.R.attr.colorPrimary)
				itemView.strokeWidth = (2 * resources.displayMetrics.density).toInt()

				tvStatus.visibility = View.VISIBLE
				when (reachability) {
					DnsViewModel.ReachabilityState.TESTING -> {
						tvStatus.text = getString(R.string.status_testing_dns)
						tvStatus.setTextColor(getThemeColor(android.R.attr.textColorSecondary))
					}

					DnsViewModel.ReachabilityState.REACHABLE -> {
						tvStatus.text = getString(R.string.status_active_reachable)
						tvStatus.setTextColor(getThemeColor(android.R.attr.colorPrimary))
					}

					DnsViewModel.ReachabilityState.UNREACHABLE -> {
						tvStatus.text = getString(R.string.warning_unreachable_dns)
						tvStatus.setTextColor(getThemeColor(R.attr.warning_color))
					}

					else -> tvStatus.visibility = View.GONE
				}
			} else {
				itemView.strokeWidth = 0
				when (reachability) {
					DnsViewModel.ReachabilityState.TESTING -> {
						tvStatus.visibility = View.VISIBLE
						tvStatus.text = getString(R.string.status_testing_dns)
						tvStatus.setTextColor(getThemeColor(android.R.attr.textColorSecondary))
					}

					DnsViewModel.ReachabilityState.UNREACHABLE -> {
						tvStatus.visibility = View.VISIBLE
						tvStatus.text = getString(R.string.warning_unreachable_dns)
						tvStatus.setTextColor(getThemeColor(R.attr.warning_color))
					}

					else -> tvStatus.visibility = View.GONE
				}
			}

			itemView.findViewById<View>(R.id.btnEditHostname)
				.setOnClickListener { showAddHostnameDialog(hostname) }

			btnDelete.isEnabled = hostnames.size > 1
			btnDelete.alpha = if (hostnames.size > 1) 1.0f else 0.5f
			btnDelete.setOnClickListener {
				showDeleteHostnameConfirmDialog(hostname)
			}

			itemView.setOnClickListener {
				if (!isActive) {
					dnsViewModel.togglePrivateDns(true, hostname)
				}
			}

			dnsHostnameListContainer.addView(itemView)
		}
	}

	private fun getThemeColor(attr: Int): Int {
		val typedValue = TypedValue()
		theme.resolveAttribute(attr, typedValue, true)
		return typedValue.data
	}

	private fun showAddHostnameDialog(existingHostname: String? = null) {
		val dialogView = LayoutInflater.from(this)
			.inflate(R.layout.dialog_text_input, findViewById(android.R.id.content), false)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)

		textInputLayout.hint = getString(R.string.dns_hostname_hint)

		if (existingHostname != null) {
			inputTextField.setText(existingHostname)
			inputTextField.setSelection(existingHostname.length)
		}

		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(
				if (existingHostname == null) getString(R.string.add_hostname) else getString(
					R.string.edit_hostname
				)
			)
			.setView(dialogView)
			.setPositiveButton(getString(R.string.ok)) { _, _ ->
				val newHostname = inputTextField.text.toString().trim()
				if (newHostname.isNotEmpty() && NetworkUtils.isValidDnsHostname(newHostname)) {
					if (existingHostname != null) {
						dnsViewModel.updateHostname(existingHostname, newHostname)
					} else {
						dnsViewModel.addHostname(newHostname)
					}
				} else if (newHostname.isNotEmpty()) {
					Toast.makeText(this, R.string.error_invalid_dns_host, Toast.LENGTH_SHORT).show()
				}
			}
			.setNegativeButton(getString(R.string.cancel), null)
			.create()

		dialog.setOnShowListener {
			inputTextField.requestFocus()
			dialog.window?.let { window ->
				WindowCompat.getInsetsController(window, inputTextField)
					.show(WindowInsetsCompat.Type.ime())
			}
		}
		dialog.show()
	}

	private fun showDeleteHostnameConfirmDialog(hostname: String) {
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.delete_hostname_confirm))
			.setPositiveButton(getString(R.string.ok)) { _, _ ->
				dnsViewModel.removeHostname(hostname)
			}
			.setNegativeButton(getString(R.string.cancel), null)
			.show()
	}

	private fun refreshSsidListView(blacklist: Set<String>) {
		ssidListContainer.removeAllViews()

		if (blacklist.isEmpty()) {
			dividerSsidList.visibility = View.GONE
			return
		}

		dividerSsidList.visibility = View.VISIBLE

		val sortedList = blacklist.toMutableList()
		sortedList.sortWith(String.CASE_INSENSITIVE_ORDER)

		val layoutInflater = LayoutInflater.from(this)
		sortedList.forEach { ssid ->
			val itemView = layoutInflater.inflate(R.layout.item_ssid, ssidListContainer, false)
			itemView.findViewById<TextView>(R.id.tvSsidName).text = ssid
			itemView.findViewById<View>(R.id.btnEditSsid)
				.setOnClickListener { showAddSsidDialog(ssid) }
			itemView.findViewById<View>(R.id.btnDeleteSsid).setOnClickListener {
				showDeleteConfirmDialog(ssid)
			}
			ssidListContainer.addView(itemView)
		}
	}

	private fun showAddSsidDialog(existingSsid: String? = null) {
		val dialogView = LayoutInflater.from(this)
			.inflate(R.layout.dialog_text_input, findViewById(android.R.id.content), false)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)

		textInputLayout.hint = getString(R.string.ssid_hint)

		if (existingSsid != null) {
			inputTextField.setText(existingSsid)
			inputTextField.setSelection(existingSsid.length)
		} else {
			val currentSsid = NetworkUtils.getCurrentWifiSsid(this)
			val blacklist = dnsViewModel.ssidBlacklist.value ?: emptySet()
			if (currentSsid != null && !blacklist.contains(currentSsid)) {
				inputTextField.setText(currentSsid)
				inputTextField.setSelection(currentSsid.length)
			}
		}

		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(if (existingSsid == null) getString(R.string.add_ssid) else getString(R.string.edit_ssid))
			.setView(dialogView)
			.setPositiveButton(getString(R.string.ok)) { _, _ ->
				val newSsidName =
					inputTextField.text.toString().trim().removePrefix("\"").removeSuffix("\"")
				if (newSsidName.isNotEmpty()) {
					if (existingSsid != null) {
						dnsViewModel.updateSsidInBlacklist(existingSsid, newSsidName)
					} else {
						dnsViewModel.addToBlacklist(newSsidName)
					}
				}
			}
			.setNegativeButton(getString(R.string.cancel), null)
			.create()

		dialog.setOnShowListener {
			inputTextField.requestFocus()
			dialog.window?.let { window ->
				WindowCompat.getInsetsController(window, inputTextField)
					.show(WindowInsetsCompat.Type.ime())
			}
		}
		dialog.show()
	}

	private fun showDeleteConfirmDialog(ssidToDelete: String) {
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.delete_ssid_confirm))
			.setPositiveButton(getString(R.string.ok)) { _, _ ->
				dnsViewModel.removeFromBlacklist(ssidToDelete)
			}
			.setNegativeButton(getString(R.string.cancel), null)
			.show()
	}

	private fun showWifiMonitoringInfoDialog() {
		val message = StringBuilder(getString(R.string.wifi_monitoring_info_text))

		message.append(getString(R.string.vpn_disclaimer_explanation))

		val powerManager = getSystemService(POWER_SERVICE) as PowerManager
		val isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(packageName)

		if (!isIgnoringBattery) {
			message.append("\n\n").append(getString(R.string.battery_optimization_explanation))
		}

		val builder = MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.wifi_monitoring_info_title))
			.setMessage(message.toString())
			.setPositiveButton(getString(R.string.ok), null)

		if (!isIgnoringBattery) {
			builder.setNeutralButton(getString(R.string.ignore_battery_optimizations)) { _, _ ->
				requestIgnoreBatteryOptimizations()
			}
		}

		builder.show()
	}

	private fun requestIgnoreBatteryOptimizations() {
		try {
			val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
				data = "package:$packageName".toUri()
			}
			startActivity(intent)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to request ignore battery optimizations", e)
			val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
			startActivity(intent)
		}
	}

	private fun updateToolbarTitle() {
		val sharedPreferences = (application as DnsToggleApplication).getPrefs()
		val dynamicName =
			sharedPreferences.getString("dynamic_app_name", getString(R.string.app_name))
		supportActionBar?.title = dynamicName
	}

	private fun requestTileUpdate() {
		TileServiceCompat.requestListeningState(
			this,
			ComponentName(this, DnsToggleService::class.java)
		)
	}

	private fun setLoadingState(isLoading: Boolean) {
		dnsToggleSwitch.isEnabled = !isLoading
		progressRootAction.visibility = if (isLoading) View.VISIBLE else View.GONE
	}

	private fun updateLauncherComponentState(isHidden: Boolean) {
		val componentName = ComponentName(this, "${packageName}.LauncherActivity")
		val newState = if (isHidden) {
			PackageManager.COMPONENT_ENABLED_STATE_DISABLED
		} else {
			PackageManager.COMPONENT_ENABLED_STATE_ENABLED
		}

		if (packageManager.getComponentEnabledSetting(componentName) != newState) {
			packageManager.setComponentEnabledSetting(
				componentName,
				newState,
				PackageManager.DONT_KILL_APP
			)
		}
	}

	private fun showKeyInvalidatedDialog() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.keystore_error_title)
			.setMessage(R.string.keystore_error_message)
			.setPositiveButton(R.string.ok) { _, _ ->
				dnsViewModel.dismissKeyInvalidatedAlert()
			}
			.setCancelable(false)
			.show()
	}

	private fun showInitialPermissionDialog() {
		if (permissionDialog?.isShowing == true) return

		val adbCommand =
			"adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
		permissionDialog = MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.permission_required))
			.setMessage(getString(R.string.permission_message))
			.setPositiveButton(getString(R.string.copy_command)) { _, _ ->
				val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
				val clip = ClipData.newPlainText(getString(R.string.adb_command_label), adbCommand)
				clipboard.setPrimaryClip(clip)
				Toast.makeText(this, getString(R.string.command_copied), Toast.LENGTH_SHORT).show()
			}
			.setNeutralButton(R.string.retry_root) { _, _ ->
				setLoadingState(true)
				lifecycleScope.launch {
					val startTime = System.currentTimeMillis()
					RootUtils.grantSecureSettingsPermission(packageName)

					// Artificial delay to prevent UI flicker and give visual confirmation
					val elapsedTime = System.currentTimeMillis() - startTime
					if (elapsedTime < 1000) {
						delay(1000.milliseconds - elapsedTime.milliseconds)
					}

					setLoadingState(false)
					updateMainPermissionUiState()

					if (!hasSecureSettingsPermission()) {
						Toast.makeText(
							this@MainActivity,
							R.string.root_grant_failed,
							Toast.LENGTH_SHORT
						).show()
						showInitialPermissionDialog()
					}
				}
			}
			.setNegativeButton(getString(R.string.ok), null)
			.show()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.main_menu, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_rename_app -> {
				showRenameAppDialog()
				true
			}

			else -> super.onOptionsItemSelected(item)
		}
	}

	private fun showRenameAppDialog() {
		val dialogView = LayoutInflater.from(this)
			.inflate(R.layout.dialog_text_input, findViewById(android.R.id.content), false)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)

		textInputLayout.hint = getString(R.string.app_name)

		val sharedPreferences = (application as DnsToggleApplication).getPrefs()
		val currentAppName =
			sharedPreferences.getString("dynamic_app_name", getString(R.string.app_name))

		if (currentAppName != null) {
			inputTextField.setText(currentAppName)
			inputTextField.setSelection(currentAppName.length)
		}

		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.rename_app))
			.setMessage(getString(R.string.rename_app_message))
			.setView(dialogView)
			.setPositiveButton(getString(R.string.ok)) { d, _ ->
				val newAppName = inputTextField.text.toString().trim()
				if (newAppName.isNotEmpty()) {
					sharedPreferences.edit { putString("dynamic_app_name", newAppName) }
					updateToolbarTitle()
					requestTileUpdate()
				}
				d.dismiss()
			}
			.setNegativeButton(getString(R.string.cancel)) { d, _ -> d.cancel() }
			.create()

		dialog.setOnShowListener {
			inputTextField.requestFocus()
			dialog.window?.let { window ->
				WindowCompat.getInsetsController(window, inputTextField)
					.show(WindowInsetsCompat.Type.ime())
			}
		}
		dialog.show()
	}

	private fun openAppSettings() {
		val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
			data = android.net.Uri.fromParts("package", packageName, null)
		}
		startActivity(intent)
	}
}
