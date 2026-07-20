package com.ericlowry.dnstoggle.ui

import android.Manifest
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsSettingsRepository
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.data.SsidItem
import com.ericlowry.dnstoggle.service.DnsToggleService
import com.ericlowry.dnstoggle.service.TileServiceCompat
import com.ericlowry.dnstoggle.util.BackupManager
import com.ericlowry.dnstoggle.util.NetworkUtils
import com.ericlowry.dnstoggle.util.PermissionHelper
import com.ericlowry.dnstoggle.util.RootUtils
import com.ericlowry.dnstoggle.util.ShizukuUtils
import com.ericlowry.dnstoggle.util.attemptSecureSettingsGrant
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "MainActivity"
		const val EXTRA_FOCUS_DNS_INPUT = "focus_dns_input"
		const val EXTRA_ENABLE_DNS_AFTER_SAVE = "enable_dns_after_save"
	}

	private lateinit var dnsViewModel: DnsViewModel
	private lateinit var hostnamesAdapter: HostnamesAdapter
	private lateinit var ssidsAdapter: SsidsAdapter

	private lateinit var btnMenu: ImageButton

	private lateinit var cardMainPermission: MaterialCardView
	private lateinit var btnFixMainPermission: Button

	private lateinit var cardOverrideStatus: MaterialCardView
	private lateinit var tvOverrideStatus: TextView

	private lateinit var privateDnsLabel: TextView
	private lateinit var progressRootAction: CircularProgressIndicator
	private lateinit var dnsToggleSwitch: MaterialSwitch
	private lateinit var addHostnameButton: ImageButton
	private lateinit var dnsHostnameListContainer: RecyclerView
	private lateinit var switchDisableDnsTest: MaterialSwitch

	private lateinit var btnSsidInfo: ImageButton
	private lateinit var addSsidButton: ImageButton
	private lateinit var permissionNoticeText: TextView
	private lateinit var btnGrantPermission: Button
	private lateinit var dividerSsidList: View
	private lateinit var ssidListContainer: RecyclerView
	private lateinit var dividerSsidSettings: View
	private lateinit var switchAutoBlacklist: MaterialSwitch
	private lateinit var switchAutoWhitelist: MaterialSwitch
	private lateinit var rowConnectivityWatchdogToggle: View
	private lateinit var switchConnectivityWatchdog: MaterialSwitch
	private lateinit var rowConnectivityWatchdogDebounce: View
	private lateinit var tvConnectivityWatchdogDebounceValue: TextView
	private lateinit var rowConnectivityWatchdogTargets: View
	private lateinit var tvConnectivityWatchdogTargetsValue: TextView

	private lateinit var btnVpnInfo: ImageButton
	private lateinit var switchVpnOverride: MaterialSwitch
	private lateinit var rowVpnDns: View
	private lateinit var tvVpnDnsValue: TextView
	private lateinit var vpnPermissionNoticeText: TextView
	private lateinit var btnGrantVpnPermission: Button

	private lateinit var switchShowToast: MaterialSwitch
	private lateinit var switchHideLauncher: MaterialSwitch
	private lateinit var rowUsbDebuggingTile: View
	private lateinit var switchUsbDebuggingTile: MaterialSwitch
	private lateinit var tvUsbDebuggingTileSummary: TextView
	private var devHitCount = 0
	private var lastDevHitTime: Long = 0
	private var devToast: Toast? = null

	private var permissionDialog: AlertDialog? = null
	private var isRedirectedFromTile = false

	private val exportLauncher =
		registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
			uri?.let { targetUri ->
				DialogHelper.showPasswordDialog(
					this,
					R.string.export_config,
					R.string.export,
					R.string.export_password_description,
					onCancel = {
						// Delete empty file it creates
						try {
							android.provider.DocumentsContract.deleteDocument(
								contentResolver,
								targetUri
							)
						} catch (e: Exception) {
							Log.e(TAG, "Failed to delete empty export file", e)
						}
					}
				) { password ->
					lifecycleScope.launch(Dispatchers.IO) {
						try {
							val rawJson = DnsSettingsRepository.exportConfigToJson()
							val encrypted = BackupManager.encryptBackup(rawJson, password)
							contentResolver.openOutputStream(targetUri)?.use { out ->
								out.write(encrypted.toByteArray())
							}
							withContext(Dispatchers.Main) {
								Toast.makeText(
									this@MainActivity,
									R.string.export_success,
									Toast.LENGTH_SHORT,
								).show()
							}
						} catch (_: Exception) {
							withContext(Dispatchers.Main) {
								Toast.makeText(
									this@MainActivity,
									R.string.export_failed,
									Toast.LENGTH_SHORT,
								).show()
							}
						}
					}
				}
			}
		}

	private val importLauncher =
		registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			uri?.let { processImportUri(it) }
		}

	private val foregroundPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions(),
	) { results ->
		val allGranted = results.entries.all { it.value }
		if (allGranted && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)) {
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
		updateVpnUiState(PermissionHelper.hasNotificationPermission(this))
		(application as DnsToggleApplication).updateWifiMonitoringRegistration()
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
		updateSsidUiState(PermissionHelper.hasSsidPermissions(this))
		handleIntentExtras(intent)
	}

	override fun onResume() {
		super.onResume()
		dnsViewModel.loadSettings()
		dnsViewModel.refreshCurrentSsid()
		updateMainPermissionUiState()
		updateSsidUiState(PermissionHelper.hasSsidPermissions(this))
		updateVpnUiState(PermissionHelper.hasNotificationPermission(this))
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntentExtras(intent)
	}

	private fun handleIntentExtras(intent: Intent?) {
		if (intent?.getBooleanExtra(
				"show_permission_dialog",
				false
			) == true && !PermissionHelper.hasSecureSettingsPermission(this)
		) {
			lifecycleScope.launch {
				attemptSecureSettingsGrant(this@MainActivity, packageName)
				updateMainPermissionUiState()
				if (!PermissionHelper.hasSecureSettingsPermission(this@MainActivity)) {
					showInitialPermissionDialog()
				}
			}
		}

		if (intent?.getBooleanExtra(EXTRA_FOCUS_DNS_INPUT, false) == true) {
			isRedirectedFromTile = true
			val enableAfterSave = intent.getBooleanExtra(EXTRA_ENABLE_DNS_AFTER_SAVE, false)
			showAddHostnameDialog(enableAfterSave = enableAfterSave)
		}

		if (intent?.action == Intent.ACTION_VIEW) {
			intent.data?.let { processImportUri(it) }
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
			findViewById<MaterialToolbar>(R.id.topAppBar)
		setSupportActionBar(toolbar)

		val accentColor = MaterialColors.getColor(toolbar, android.R.attr.colorPrimary)
		toolbar.logo?.setTint(accentColor)
	}

	private fun initializeViews() {
		btnMenu = findViewById(R.id.btnMenu)

		cardMainPermission = findViewById(R.id.cardMainPermissionLayout)
		btnFixMainPermission = findViewById(R.id.btnFixMainPermission)

		cardOverrideStatus = findViewById(R.id.cardOverrideStatus)
		tvOverrideStatus = findViewById(R.id.tvOverrideStatus)

		privateDnsLabel = findViewById(R.id.tvToggleLabel)
		progressRootAction = findViewById(R.id.progressRootAction)
		dnsToggleSwitch = findViewById(R.id.switchPrivateDns)
		addHostnameButton = findViewById(R.id.btnAddHostname)
		dnsHostnameListContainer = findViewById(R.id.dnsHostnameListContainer)
		switchDisableDnsTest = findViewById(R.id.switchDisableDnsTest)

		btnSsidInfo = findViewById(R.id.btnSsidInfo)
		addSsidButton = findViewById(R.id.btnAddSsid)
		permissionNoticeText = findViewById(R.id.tvPermissionNotice)
		btnGrantPermission = findViewById(R.id.btnGrantPermission)
		dividerSsidList = findViewById(R.id.dividerSsidList)
		ssidListContainer = findViewById(R.id.ssidListContainer)
		dividerSsidSettings = findViewById(R.id.dividerSsidSettings)
		switchAutoBlacklist = findViewById(R.id.switchAutoBlacklist)
		switchAutoWhitelist = findViewById(R.id.switchAutoWhitelist)
		rowConnectivityWatchdogToggle = findViewById(R.id.rowConnectivityWatchdogToggle)
		switchConnectivityWatchdog = findViewById(R.id.switchConnectivityWatchdog)
		rowConnectivityWatchdogDebounce = findViewById(R.id.rowConnectivityWatchdogDebounce)
		tvConnectivityWatchdogDebounceValue = findViewById(R.id.tvConnectivityWatchdogDebounceValue)
		rowConnectivityWatchdogTargets = findViewById(R.id.rowConnectivityWatchdogTargets)
		tvConnectivityWatchdogTargetsValue = findViewById(R.id.tvConnectivityWatchdogTargetsValue)

		btnVpnInfo = findViewById(R.id.btnVpnInfo)
		switchVpnOverride = findViewById(R.id.switchVpnOverride)
		rowVpnDns = findViewById(R.id.rowVpnDns)
		tvVpnDnsValue = findViewById(R.id.tvVpnDnsValue)
		vpnPermissionNoticeText = findViewById(R.id.tvVpnPermissionNotice)
		btnGrantVpnPermission = findViewById(R.id.btnGrantVpnPermission)

		switchShowToast = findViewById(R.id.switchShowToast)
		switchHideLauncher = findViewById(R.id.switchHideLauncher)
		rowUsbDebuggingTile = findViewById(R.id.rowUsbDebuggingTileLayout)
		switchUsbDebuggingTile = findViewById(R.id.switchUsbDebuggingTile)
		tvUsbDebuggingTileSummary = findViewById(R.id.tvUsbDebuggingTileSummary)

		setupFixPermissionButton()
		setupUsbDebuggingTile()
		privateDnsLabel.text = getString(R.string.private_dns)

		val tvAppVersion = findViewById<TextView>(R.id.tvAppVersion)

		try {
			val pInfo = packageManager.getPackageInfo(packageName, 0)
			tvAppVersion.text = getString(R.string.app_version_format, pInfo.versionName)
		} catch (_: Exception) {
			tvAppVersion.visibility = View.GONE
		}
	}

	private fun observeViewModel() {
		fun updateHostnamesMetadata() {
			val mode = dnsViewModel.privateDnsMode.value
			val specifier = dnsViewModel.privateDnsSpecifier.value
			val reachability = dnsViewModel.dnsReachability.value
			val isEnabled = (mode == Constants.DNS_MODE_HOSTNAME)

			dnsToggleSwitch.isChecked = isEnabled
			hostnamesAdapter.updateMetadata(reachability, specifier, isEnabled)
		}

		dnsViewModel.privateDnsMode.observe(this) { updateHostnamesMetadata() }
		dnsViewModel.privateDnsSpecifier.observe(this) { updateHostnamesMetadata() }
		dnsViewModel.dnsReachability.observe(this) { updateHostnamesMetadata() }

		dnsViewModel.dnsHostnames.observe(this) { hostnames ->
			(dnsHostnameListContainer.adapter as? HostnamesAdapter)?.submitList(hostnames)

			// Also refresh VPN display name in case a label was added/changed
			val vpnHostname = dnsViewModel.vpnDnsHostname.value
			if (vpnHostname != null) {
				tvVpnDnsValue.text = hostnames.find { it.hostname == vpnHostname }
					?.getDisplayName() ?: vpnHostname
			}
		}

		dnsViewModel.ssidBlacklist.observe(this) { blacklist ->
			refreshSsidListView(blacklist)
		}

		dnsViewModel.autoDetectedBlacklist.observe(this) {
			refreshSsidListView(dnsViewModel.ssidBlacklist.value ?: emptySet())
		}

		dnsViewModel.currentSsid.observe(this) { ssid ->
			ssidsAdapter.updateActiveSsid(ssid)
		}

		dnsViewModel.autoBlacklistEnabled.observe(this) { enabled ->
			switchAutoBlacklist.isChecked = enabled
		}

		dnsViewModel.autoWhitelistEnabled.observe(this) { enabled ->
			switchAutoWhitelist.isChecked = enabled
		}

		dnsViewModel.connectivityWatchdogEnabled.observe(this) { enabled ->
			switchConnectivityWatchdog.isChecked = enabled
			rowConnectivityWatchdogDebounce.isEnabled = enabled
			rowConnectivityWatchdogDebounce.alpha = if (enabled) 1.0f else 0.5f
			rowConnectivityWatchdogTargets.isEnabled = enabled
			rowConnectivityWatchdogTargets.alpha = if (enabled) 1.0f else 0.5f
		}

		dnsViewModel.connectivityWatchdogDebounceSeconds.observe(this) { seconds ->
			tvConnectivityWatchdogDebounceValue.text =
				getString(R.string.connectivity_watchdog_debounce_seconds_format, seconds)
		}

		dnsViewModel.connectivityWatchdogProbeTargets.observe(this) { targets ->
			tvConnectivityWatchdogTargetsValue.text = targets
		}

		dnsViewModel.disableDnsTest.observe(this) { disabled ->
			switchDisableDnsTest.isChecked = disabled
		}

		dnsViewModel.showToastEnabled.observe(this) { enabled ->
			switchShowToast.isChecked = enabled
		}

		dnsViewModel.vpnOverrideEnabled.observe(this) { enabled ->
			switchVpnOverride.isChecked = enabled
			updateVpnUiState(PermissionHelper.hasNotificationPermission(this))
		}

		dnsViewModel.vpnDnsHostname.observe(this) { hostname ->
			tvVpnDnsValue.text = if (hostname == null) {
				getString(R.string.automatic_off)
			} else {
				dnsViewModel.dnsHostnames.value
					?.find { it.hostname == hostname }
					?.getDisplayName() ?: hostname
			}
		}

		dnsViewModel.vpnHostnameRemovedWarning.observe(this) { show ->
			if (show) {
				AlertDialog.Builder(this)
					.setTitle(R.string.vpn_override_title)
					.setMessage(R.string.vpn_hostname_removed_warning)
					.setPositiveButton(R.string.ok) { _, _ ->
						dnsViewModel.dismissVpnHostnameWarning()
					}
					.show()
			}
		}

		dnsViewModel.isInVpnOverride.observe(this) { _ -> updateOverrideStatusUi() }
		dnsViewModel.activeSsidOverride.observe(this) { _ -> updateOverrideStatusUi() }

		dnsViewModel.hideLauncherIcon.observe(this) { isHidden ->
			switchHideLauncher.isChecked = isHidden
			updateLauncherComponentState(isHidden)
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
		setupDnsToggle()
		setupAddHostname()
		setupMenu()
		setupAutoSettings()
		setupWatchdog()
		setupVpnSettings()
		setupAddSsid()
		setupHostnamesRecyclerView()
		setupSsidsRecyclerView()
		setupFooter()

		val prefs = (application as DnsToggleApplication).getPrefs()
		if (prefs.getBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, false)) {
			rowUsbDebuggingTile.visibility = View.VISIBLE
		}

		setupVersionClick()
	}

	private fun setupDnsToggle() {
		dnsToggleSwitch.setOnClickListener {
			val isChecked = dnsToggleSwitch.isChecked

			if (PermissionHelper.hasSecureSettingsPermission(this)) {
				dnsViewModel.togglePrivateDns(isChecked)
				requestTileUpdate()
			} else {
				val toastMsgRes = when {
					ShizukuUtils.isAvailable() -> R.string.toast_attempting_shizuku
					RootUtils.isAvailable() -> R.string.toast_attempting_root
					else -> R.string.toast_attempting_fallback
				}
				Toast.makeText(this@MainActivity, toastMsgRes, Toast.LENGTH_SHORT).show()

				// Attempt grant again
				setLoadingState(true)
				lifecycleScope.launch {
					attemptSecureSettingsGrant(this@MainActivity, packageName)
					setLoadingState(false)
					updateMainPermissionUiState()

					if (PermissionHelper.hasSecureSettingsPermission(this@MainActivity)) {
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
	}

	private fun setupAddHostname() {
		addHostnameButton.setOnClickListener {
			showAddHostnameDialog()
		}
	}

	private fun setupMenu() {
		btnMenu.setOnClickListener {
			showMenuBottomSheet()
		}
	}

	private fun setupAutoSettings() {
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
	}

	private fun setupWatchdog() {
		switchConnectivityWatchdog.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setConnectivityWatchdogEnabled(isChecked)
		}

		rowConnectivityWatchdogDebounce.setOnClickListener {
			showConnectivityWatchdogDebounceDialog()
		}

		rowConnectivityWatchdogTargets.setOnClickListener {
			val current = dnsViewModel.connectivityWatchdogProbeTargets.value
				?: Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS
			DialogHelper.showTextInputDialog(
				activity = this,
				titleResId = R.string.connectivity_watchdog_targets_title,
				hintResId = R.string.connectivity_watchdog_targets_hint,
				initialValue = current,
				onSave = { dnsViewModel.setConnectivityWatchdogProbeTargets(it) }
			)
		}
	}

	private fun setupVpnSettings() {
		switchDisableDnsTest.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setDisableDnsTest(isChecked)
		}

		switchShowToast.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setShowToast(isChecked)
		}

		switchHideLauncher.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setHideLauncherIcon(isChecked)
		}

		switchVpnOverride.setOnCheckedChangeListener { _, isChecked ->
			dnsViewModel.setVpnOverrideEnabled(isChecked)
		}

		rowVpnDns.setOnClickListener {
			showVpnDnsSelectionDialog()
		}

		btnGrantVpnPermission.setOnClickListener {
			checkNotificationPermission()
		}

		btnVpnInfo.setOnClickListener { showWifiMonitoringInfoDialog() }
	}

	private fun setupAddSsid() {
		addSsidButton.setOnClickListener {
			if (PermissionHelper.hasSsidPermissions(this)) {
				showAddSsidDialog()
			} else {
				requestSsidPermissions()
			}
		}

		btnGrantPermission.setOnClickListener { requestSsidPermissions() }
		btnSsidInfo.setOnClickListener { showWifiMonitoringInfoDialog() }
	}

	private fun setupFooter() {
		val btnGithub = findViewById<Button>(R.id.btnGithub)
		val btnSupport = findViewById<Button>(R.id.btnSupport)

		btnGithub.setOnClickListener {
			openUrl("https://github.com/ELowry/DNSToggle")
		}
		btnSupport.setOnClickListener {
			openUrl("https://github.com/ELowry/DNSToggle/issues/new/choose")
		}
	}

	private fun setupFixPermissionButton() {
		btnFixMainPermission.setOnClickListener {
			if (PermissionHelper.hasSecureSettingsPermission(this)) {
				updateMainPermissionUiState()
			} else {
				val toastMsgRes = when {
					ShizukuUtils.isAvailable() -> R.string.toast_attempting_shizuku
					RootUtils.isAvailable() -> R.string.toast_attempting_root
					else -> R.string.toast_attempting_fallback
				}
				Toast.makeText(this@MainActivity, toastMsgRes, Toast.LENGTH_SHORT).show()

				setLoadingState(true)
				lifecycleScope.launch {
					val success = attemptSecureSettingsGrant(this@MainActivity, packageName)
					setLoadingState(false)
					updateMainPermissionUiState()

					if (!PermissionHelper.hasSecureSettingsPermission(this@MainActivity)) {
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

	private fun setupUsbDebuggingTile() {
		val prefs = (application as DnsToggleApplication).getPrefs()

		switchUsbDebuggingTile.isChecked =
			prefs.getBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, false)

		switchUsbDebuggingTile.setOnCheckedChangeListener { _, isChecked ->
			prefs.edit { putBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, isChecked) }

			if (!isChecked) {
				rowUsbDebuggingTile.visibility = View.GONE
				devHitCount = 0
			}

			(application as DnsToggleApplication).updateUsbDebuggingTileAvailability()
		}
	}

	private fun setupVersionClick() {
		val tvAppVersion = findViewById<TextView>(R.id.tvAppVersion)
		tvAppVersion.setOnClickListener {
			val prefs = (application as DnsToggleApplication).getPrefs()
			if (prefs.getBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, false)) {
				return@setOnClickListener
			}

			val isDevMode = Settings.Global.getInt(
				contentResolver,
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
						this,
						getString(R.string.usb_debugging_tile_steps, remaining),
						Toast.LENGTH_SHORT
					)
					devToast?.show()
				}
			} else if (devHitCount >= 5) {
				devToast?.cancel()
				devToast = Toast.makeText(
					this,
					getString(R.string.usb_debugging_tile_enabled),
					Toast.LENGTH_SHORT
				)
				devToast?.show()
				prefs.edit { putBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, true) }

				rowUsbDebuggingTile.visibility = View.VISIBLE
				switchUsbDebuggingTile.isChecked = true

				(application as DnsToggleApplication).updateUsbDebuggingTileAvailability()
			}
		}
	}

	private fun updateOverrideStatusUi() {
		val vpnActive = dnsViewModel.isInVpnOverride.value == true
		val activeSsid = dnsViewModel.activeSsidOverride.value

		when {
			vpnActive -> {
				cardOverrideStatus.visibility = View.VISIBLE
				tvOverrideStatus.text = getString(R.string.status_override_vpn)
			}

			activeSsid != null -> {
				cardOverrideStatus.visibility = View.VISIBLE
				tvOverrideStatus.text = getString(R.string.status_override_ssid, activeSsid)
			}

			else -> {
				cardOverrideStatus.visibility = View.GONE
			}
		}
	}

	private fun updateMainPermissionUiState() {
		if (PermissionHelper.hasSecureSettingsPermission(this)) {
			if (::cardMainPermission.isInitialized) {
				cardMainPermission.visibility = View.GONE
			}
		} else {
			cardMainPermission.visibility = View.VISIBLE
		}
	}

	private fun checkSsidPermissions(requestIfNotGranted: Boolean = false) {
		val allGranted = PermissionHelper.hasSsidPermissions(this)
		updateSsidUiState(allGranted)
		if (!allGranted && requestIfNotGranted) {
			requestSsidPermissions()
		} else if (allGranted) {
			checkNotificationPermission()
		}
	}

	private fun requestSsidPermissions() {
		val permissions = PermissionHelper.getForegroundSsidPermissions()
		val prefs = (application as DnsToggleApplication).getPrefs()

		if (prefs.getBoolean("has_requested_ssid_perms", false)) {
			val isPermanentlyDenied = permissions.any { perm ->
				ContextCompat.checkSelfPermission(
					this,
					perm
				) != PackageManager.PERMISSION_GRANTED &&
						!shouldShowRequestPermissionRationale(perm)
			}
			if (isPermanentlyDenied) {
				DialogHelper.showPermissionDeniedDialog(this) { openAppSettings() }
				return
			}
		}
		prefs.edit { putBoolean("has_requested_ssid_perms", true) }
		foregroundPermissionLauncher.launch(permissions)
	}

	private fun showBackgroundLocationRationale() {
		DialogHelper.showBackgroundLocationRationale(
			context = this,
			onAccept = {
				requestBackgroundLocationPermission()
			},
			onDecline = {
				updateSsidUiState(true)
				(application as DnsToggleApplication).updateWifiMonitoringRegistration()
				checkNotificationPermission()
			}
		)
	}

	private fun requestBackgroundLocationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
		}
	}

	private fun checkNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (!PermissionHelper.hasNotificationPermission(this)) {
				val prefs = (application as DnsToggleApplication).getPrefs()
				val hasRequestedBefore = prefs.getBoolean("has_requested_notif_perms", false)

				if (hasRequestedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
					DialogHelper.showPermissionDeniedDialog(this) { openAppSettings() }
				} else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
					DialogHelper.showNotificationPermissionRationale(this) {
						prefs.edit { putBoolean("has_requested_notif_perms", true) }
						notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
					}
				} else {
					prefs.edit { putBoolean("has_requested_notif_perms", true) }
					notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
				}
			}
		}
	}

	private fun updateSsidUiState(hasPermission: Boolean) {
		if (hasPermission) {
			permissionNoticeText.visibility = View.GONE
			btnGrantPermission.visibility = View.GONE
			addSsidButton.isEnabled = true
			switchAutoBlacklist.isEnabled = true
			switchAutoWhitelist.isEnabled = true
			ssidListContainer.alpha = 1.0f

			val watchdogEnabled = dnsViewModel.connectivityWatchdogEnabled.value ?: false
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

	private fun updateVpnUiState(hasPermission: Boolean) {
		if (hasPermission) {
			vpnPermissionNoticeText.visibility = View.GONE
			btnGrantVpnPermission.visibility = View.GONE
			switchVpnOverride.isEnabled = true
			rowVpnDns.isEnabled = dnsViewModel.vpnOverrideEnabled.value == true
			rowVpnDns.alpha = if (dnsViewModel.vpnOverrideEnabled.value == true) 1.0f else 0.5f
		} else {
			vpnPermissionNoticeText.visibility = View.VISIBLE
			btnGrantVpnPermission.visibility = View.VISIBLE
			switchVpnOverride.isEnabled = false
			rowVpnDns.isEnabled = false
			rowVpnDns.alpha = 0.5f
		}
	}

	private fun setupHostnamesRecyclerView() {
		hostnamesAdapter = HostnamesAdapter(
			onEditClick = { hostname -> showAddHostnameDialog(hostname) },
			onDeleteClick = { hostname -> showDeleteHostnameConfirmDialog(hostname) },
			onItemClick = { hostname -> dnsViewModel.togglePrivateDns(true, hostname) },
			onAddInPlaceClick = { hostname ->
				dnsViewModel.addHostname(hostname)
				Toast.makeText(this, R.string.hostname_saved, Toast.LENGTH_SHORT).show()
			}
		)
		dnsHostnameListContainer.adapter = hostnamesAdapter

		val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
			ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
		) {
			override fun isLongPressDragEnabled(): Boolean {
				return (dnsHostnameListContainer.adapter?.itemCount ?: 0) > 1
			}

			override fun getDragDirs(
				recyclerView: RecyclerView,
				viewHolder: RecyclerView.ViewHolder
			): Int {
				val position = viewHolder.bindingAdapterPosition
				val item = hostnamesAdapter.currentList.getOrNull(position)
				if (item?.isUnsaved == true) {
					return 0 // Disable grabbing the placeholder
				}
				return super.getDragDirs(recyclerView, viewHolder)
			}

			override fun canDropOver(
				recyclerView: RecyclerView,
				current: RecyclerView.ViewHolder,
				target: RecyclerView.ViewHolder
			): Boolean {
				val targetItem =
					hostnamesAdapter.currentList.getOrNull(target.bindingAdapterPosition)
				if (targetItem?.isUnsaved == true) {
					return false // Prevent other items from displacing the placeholder
				}
				return super.canDropOver(recyclerView, current, target)
			}

			override fun onMove(
				recyclerView: RecyclerView,
				viewHolder: RecyclerView.ViewHolder,
				target: RecyclerView.ViewHolder
			): Boolean {
				val fromPos = viewHolder.bindingAdapterPosition
				val toPos = target.bindingAdapterPosition

				// Block reordering into position 0 if that item is unsaved
				val targetItem = hostnamesAdapter.currentList.getOrNull(toPos)
				if (targetItem?.isUnsaved == true) return false

				hostnamesAdapter.moveItem(fromPos, toPos)
				return true
			}

			override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

			override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
				super.onSelectedChanged(viewHolder, actionState)
				if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
					val currentList = hostnamesAdapter.currentList
					dnsViewModel.dnsHostnames.value?.let { original ->
						if (currentList != original.toList()) {
							dnsViewModel.updateHostnameOrder(currentList)
						}
					}
				}
			}
		})
		itemTouchHelper.attachToRecyclerView(dnsHostnameListContainer)
	}

	private fun showAddHostnameDialog(
		existingHostname: String? = null,
		enableAfterSave: Boolean = false
	) {
		val existingEntry = existingHostname?.let { host ->
			dnsViewModel.dnsHostnames.value?.find { it.hostname == host }
		}

		DialogHelper.showAddHostnameDialog(
			this,
			existingHostname,
			existingEntry?.label
		) { newHostname, newLabel ->
			if (NetworkUtils.isValidDnsHostname(newHostname)) {
				if (existingHostname != null) {
					dnsViewModel.updateHostname(existingHostname, newHostname, newLabel)
				} else {
					dnsViewModel.addHostname(newHostname, newLabel)
				}

				if (enableAfterSave) {
					dnsViewModel.togglePrivateDns(true, newHostname)
				}
			} else {
				Toast.makeText(this, R.string.error_invalid_dns_host, Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun showDeleteHostnameConfirmDialog(hostname: String) {
		DialogHelper.showDeleteConfirmation(this, R.string.delete_hostname_confirm) {
			dnsViewModel.removeHostname(hostname)
		}
	}

	private fun setupSsidsRecyclerView() {
		ssidsAdapter = SsidsAdapter(
			onEditClick = { ssid -> showAddSsidDialog(ssid) },
			onDeleteClick = { ssid -> showDeleteConfirmDialog(ssid) },
			onConfirmClick = { ssid ->
				dnsViewModel.promoteSsidToPermanent(ssid)
				Toast.makeText(this, R.string.ssid_saved, Toast.LENGTH_SHORT).show()
			}
		)
		ssidListContainer.adapter = ssidsAdapter
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

		val autoDetected = dnsViewModel.autoDetectedBlacklist.value ?: emptySet()
		val items = blacklist.map { ssid ->
			SsidItem(ssid, isAutoDetected = autoDetected.contains(ssid))
		}.sortedWith { a, b ->
			String.CASE_INSENSITIVE_ORDER.compare(a.ssid, b.ssid)
		}

		ssidsAdapter.submitList(items)
	}

	private fun showAddSsidDialog(existingSsid: String? = null) {
		val currentSsid = NetworkUtils.getCurrentWifiSsid(this)
		val blacklist = dnsViewModel.ssidBlacklist.value ?: emptySet()
		val suggestedSsid =
			if (currentSsid != null && !blacklist.contains(currentSsid)) currentSsid else null

		DialogHelper.showAddSsidDialog(this, existingSsid, suggestedSsid) { newSsidName ->
			if (existingSsid != null) {
				dnsViewModel.updateSsidInBlacklist(existingSsid, newSsidName)
			} else {
				dnsViewModel.addToBlacklist(newSsidName)
			}
		}
	}

	private fun showDeleteConfirmDialog(ssidToDelete: String) {
		DialogHelper.showDeleteConfirmation(this, R.string.delete_ssid_confirm) {
			dnsViewModel.removeFromBlacklist(ssidToDelete)
		}
	}

	private fun showWifiMonitoringInfoDialog() {
		val powerManager = getSystemService(POWER_SERVICE) as PowerManager
		val isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(packageName)

		DialogHelper.showWifiMonitoringInfo(this, isIgnoringBattery) {
			requestIgnoreBatteryOptimizations()
		}
	}

	private fun requestIgnoreBatteryOptimizations() {
		val manufacturer = Build.MANUFACTURER.lowercase()

		if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains(
				"poco"
			)
		) {
			try {
				// Xiaomi "Hidden Apps / Battery Saver"
				val intent = Intent().apply {
					component = ComponentName(
						"com.miui.powerkeeper",
						"com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
					)
					putExtra("package_name", packageName)
					putExtra("package_label", getString(R.string.app_name))
				}
				startActivity(intent)
				return
			} catch (e: Exception) {
				Log.e(TAG, "Failed to open HyperOS Battery Saver", e)
			}

			try {
				// Xiaomi "Autostart" menu
				val intent = Intent().apply {
					component = ComponentName(
						"com.miui.securitycenter",
						"com.miui.permcenter.autostart.AutoStartManagementActivity"
					)
				}
				startActivity(intent)
				return
			} catch (e: Exception) {
				Log.e(TAG, "Failed to open HyperOS Autostart", e)
			}
		}

		// Standard Android settings
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
		DialogHelper.showKeyInvalidatedDialog(this) {
			dnsViewModel.dismissKeyInvalidatedAlert()
		}
	}

	private fun showImportConfirmationDialog(onConfirm: () -> Unit) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.import_config)
			.setMessage(R.string.import_confirmation_message)
			.setPositiveButton(R.string.import_action) { _, _ -> onConfirm() }
			.setNegativeButton(R.string.cancel, null)
			.show()
	}

	private fun showInitialPermissionDialog() {
		if (permissionDialog?.isShowing == true) return

		permissionDialog = DialogHelper.showSecureSettingsPermissionDialog(
			context = this,
			packageName = packageName,
			onCopyCommand = { adbCommand ->
				val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
				val clip = ClipData.newPlainText(getString(R.string.adb_command_label), adbCommand)
				clipboard.setPrimaryClip(clip)
				Toast.makeText(this, getString(R.string.command_copied), Toast.LENGTH_SHORT).show()
			},
			onAttemptElevatedGrant = {
				val toastMsgRes = when {
					ShizukuUtils.isAvailable() -> R.string.toast_attempting_shizuku
					RootUtils.isAvailable() -> R.string.toast_attempting_root
					else -> R.string.toast_attempting_fallback
				}
				Toast.makeText(this@MainActivity, toastMsgRes, Toast.LENGTH_SHORT).show()

				setLoadingState(true)
				lifecycleScope.launch {
					val startTime = System.currentTimeMillis()
					attemptSecureSettingsGrant(this@MainActivity, packageName)

					val elapsedTime = System.currentTimeMillis() - startTime
					if (elapsedTime < 1000) delay(1000.milliseconds - elapsedTime.milliseconds)

					setLoadingState(false)
					updateMainPermissionUiState()

					if (!PermissionHelper.hasSecureSettingsPermission(this@MainActivity)) {
						Toast.makeText(
							this@MainActivity,
							R.string.grant_failed,
							Toast.LENGTH_SHORT
						).show()
						showInitialPermissionDialog()
					}
				}
			}
		)
	}

	private fun showMenuBottomSheet() {
		val bottomSheet = BottomSheetDialog(this)
		val view = layoutInflater.inflate(
			R.layout.dialog_menu_bottom_sheet,
			findViewById(android.R.id.content),
			false
		)
		bottomSheet.setContentView(view)

		ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom)
			insets
		}

		view.findViewById<MaterialButton>(R.id.btnMenuRename).setOnClickListener {
			bottomSheet.dismiss()
			showRenameAppDialog()
		}

		view.findViewById<MaterialButton>(R.id.btnMenuExport).setOnClickListener {
			bottomSheet.dismiss()
			exportLauncher.launch("DNSToggle_Backup.dnstoggle")
		}

		view.findViewById<MaterialButton>(R.id.btnMenuImport).setOnClickListener {
			bottomSheet.dismiss()
			importLauncher.launch(arrayOf("*/*"))
		}

		bottomSheet.show()
	}

	private fun processImportUri(uri: Uri) {
		DialogHelper.showPasswordDialog(
			this,
			R.string.import_config,
			R.string.import_action
		) { password ->
			lifecycleScope.launch(Dispatchers.IO) {
				try {
					val encryptedData =
						contentResolver.openInputStream(uri)?.bufferedReader()
							.use { reader ->
								reader?.readText()
							} ?: return@launch

					val decryptedJson = BackupManager.decryptBackup(encryptedData, password)

					withContext(Dispatchers.Main) {
						if (decryptedJson != null) {
							showImportConfirmationDialog {
								val isValidJson =
									DnsSettingsRepository.importConfigFromJson(decryptedJson)
								if (isValidJson) {
									dnsViewModel.loadSettings()
									Toast.makeText(
										this@MainActivity,
										R.string.import_success,
										Toast.LENGTH_SHORT,
									).show()
								} else {
									Toast.makeText(
										this@MainActivity,
										R.string.import_failed,
										Toast.LENGTH_SHORT,
									).show()
								}
							}
						} else {
							Toast.makeText(
								this@MainActivity,
								R.string.import_failed_password,
								Toast.LENGTH_SHORT,
							).show()
						}
					}
				} catch (_: Exception) {
					withContext(Dispatchers.Main) {
						Toast.makeText(
							this@MainActivity,
							R.string.import_failed,
							Toast.LENGTH_SHORT,
						).show()
					}
				}
			}
		}
	}

	private fun showRenameAppDialog() {
		val sharedPreferences = (application as DnsToggleApplication).getPrefs()
		val currentAppName =
			sharedPreferences.getString("dynamic_app_name", getString(R.string.app_name))

		DialogHelper.showRenameAppDialog(this, currentAppName) { newAppName ->
			sharedPreferences.edit { putString("dynamic_app_name", newAppName) }
			updateToolbarTitle()
			requestTileUpdate()
		}
	}

	private fun openAppSettings() {
		val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
			data = Uri.fromParts("package", packageName, null)
		}
		startActivity(intent)
	}

	private fun showVpnDnsSelectionDialog() {
		val hostnames = dnsViewModel.dnsHostnames.value ?: emptyList()
		if (hostnames.isEmpty()) return

		if (hostnames.size == 1) {
			val hostname = hostnames.first().hostname
			val current = dnsViewModel.vpnDnsHostname.value
			val newValue = if (current == hostname) null else hostname
			dnsViewModel.setVpnDnsHostname(newValue)
			return
		}

		val dialogView = LayoutInflater.from(this)
			.inflate(R.layout.dialog_dns_selection, findViewById(android.R.id.content), false)

		val dialog = Dialog(this)
		dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
		dialog.setContentView(dialogView)

		val tvPopupTitle = dialogView.findViewById<TextView>(R.id.tvPopupTitle)
		tvPopupTitle.text = getString(R.string.vpn_dns_label)

		val listContainer = dialogView.findViewById<LinearLayout>(R.id.dnsListContainer)
		val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnSettings)
		btnCancel.text = getString(R.string.cancel)
		btnCancel.setOnClickListener { dialog.dismiss() }

		val currentVpnDns = dnsViewModel.vpnDnsHostname.value
		val totalItems = hostnames.size + 1

		hostnames.forEachIndexed { index, dnsEntry ->
			val hostname = dnsEntry.hostname
			val isActive = (hostname == currentVpnDns)
			val itemView = createDnsListItem(
				listContainer,
				dnsEntry.getDisplayName(),
				dnsEntry.label?.let { hostname },
				isActive,
				index,
				totalItems
			) {
				dnsViewModel.setVpnDnsHostname(hostname)
				dialog.dismiss()
			}
			listContainer.addView(itemView)
		}

		val isAutoActive = (currentVpnDns == null)
		val autoItemView = createDnsListItem(
			listContainer,
			getString(R.string.automatic_off),
			null,
			isAutoActive,
			totalItems - 1,
			totalItems
		) {
			dnsViewModel.setVpnDnsHostname(null)
			dialog.dismiss()
		}
		listContainer.addView(autoItemView)

		dialog.show()
	}

	private fun showConnectivityWatchdogDebounceDialog() {
		val presets = listOf(10, 15, 30, 60)
		val currentValue = dnsViewModel.connectivityWatchdogDebounceSeconds.value
			?: Constants.CONNECTIVITY_WATCHDOG_DEFAULT_DEBOUNCE_SECONDS

		// Create the string array for the dialog
		val options = presets.map { "${it}s" }.toMutableList()
		options.add(getString(R.string.connectivity_watchdog_debounce_custom))

		// Find the currently selected index
		val checkedItem =
			if (presets.contains(currentValue)) presets.indexOf(currentValue) else options.lastIndex

		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.connectivity_watchdog_debounce_label)
			.setSingleChoiceItems(options.toTypedArray(), checkedItem) { dialog, which ->
				if (which < presets.size) {
					// A preset was selected
					dnsViewModel.setConnectivityWatchdogDebounceSeconds(presets[which])
					dialog.dismiss()
				} else {
					// "Custom..." was selected
					dialog.dismiss()
					DialogHelper.showNumberInputDialog(
						this,
						R.string.connectivity_watchdog_debounce_label,
						R.string.connectivity_watchdog_debounce_label,
						currentValue,
						5,
						300,
						R.string.connectivity_watchdog_debounce_invalid
					) { seconds ->
						dnsViewModel.setConnectivityWatchdogDebounceSeconds(seconds)
					}
				}
			}
			.setNegativeButton(R.string.cancel, null)
			.show()
	}

	private fun createDnsListItem(
		parent: ViewGroup,
		text: String,
		secondaryText: String?,
		isActive: Boolean,
		position: Int,
		totalItems: Int,
		onClick: () -> Unit
	): View {
		val itemView = LayoutInflater.from(this).inflate(R.layout.item_dns_selection, parent, false)
		val listItemLayout = itemView as ListItemLayout
		val cardView = itemView.findViewById<ListItemCardView>(R.id.listItemCard)
		val textView = itemView.findViewById<TextView>(R.id.tvHostname)
		val secondaryTextView = itemView.findViewById<TextView>(R.id.tvSecondaryHostname)
		val radioButton = itemView.findViewById<MaterialRadioButton>(R.id.radioDns)

		textView.text = text
		if (secondaryText != null) {
			secondaryTextView.text = secondaryText
			secondaryTextView.visibility = View.VISIBLE
		} else {
			secondaryTextView.visibility = View.GONE
		}
		cardView.isChecked = isActive
		radioButton.isChecked = isActive

		listItemLayout.updateAppearance(position, totalItems)
		cardView.setOnClickListener { onClick() }

		return itemView
	}

	private fun openUrl(url: String) {
		try {
			val intent = Intent(Intent.ACTION_VIEW, url.toUri())
			startActivity(intent)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to open URL", e)
		}
	}
}
