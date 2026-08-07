package com.ericlowry.dnstoggle.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.service.DnsToggleService
import com.ericlowry.dnstoggle.service.TileServiceCompat
import com.ericlowry.dnstoggle.ui.controller.BackupController
import com.ericlowry.dnstoggle.ui.controller.DnsSectionController
import com.ericlowry.dnstoggle.ui.controller.MiscSettingsController
import com.ericlowry.dnstoggle.ui.controller.SsidSectionController
import com.ericlowry.dnstoggle.ui.controller.VpnSectionController
import com.ericlowry.dnstoggle.ui.dialog.CommonDialogHelper
import com.ericlowry.dnstoggle.ui.dialog.PermissionDialogHelper
import com.ericlowry.dnstoggle.ui.dialog.SsidDialogHelper
import com.ericlowry.dnstoggle.ui.dialog._InfoNoticeHelper // TEMPORARY BETA INFO
import com.ericlowry.dnstoggle.util.PermissionHelper
import com.ericlowry.dnstoggle.util.RootUtils
import com.ericlowry.dnstoggle.util.ShizukuUtils
import com.ericlowry.dnstoggle.util.attemptSecureSettingsGrant
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import com.google.android.material.R as MaterialR

class MainActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "MainActivity"
		const val EXTRA_FOCUS_DNS_INPUT = "focus_dns_input"
		const val EXTRA_ENABLE_DNS_AFTER_SAVE = "enable_dns_after_save"
	}

	private lateinit var dnsViewModel: DnsViewModel
	private lateinit var permissionHandler: MainPermissionHandler

	private lateinit var dnsController: DnsSectionController
	private lateinit var ssidController: SsidSectionController
	private lateinit var vpnController: VpnSectionController
	private lateinit var backupController: BackupController
	private lateinit var miscController: MiscSettingsController

	private lateinit var btnMenu: ImageButton
	private lateinit var cardMainPermission: MaterialCardView
	private lateinit var btnFixMainPermission: Button
	private lateinit var cardOverrideStatus: MaterialCardView
	private lateinit var tvOverrideStatus: TextView
	private lateinit var mainScrollView: NestedScrollView

	private var permissionDialog: AlertDialog? = null
	private var scrollSpring: SpringAnimation? = null
	private var lastSpringValue = 0f

	private val focusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
		if (newFocus != null && !mainScrollView.isInTouchMode) {
			smoothScrollToCenter(newFocus)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		dnsViewModel = ViewModelProvider(this)[DnsViewModel::class.java]

		permissionHandler = MainPermissionHandler(
			activity = this,
			onSsidPermissionChanged = { hasPermission -> ssidController.updateUiState(hasPermission) },
			onNotificationPermissionChanged = { hasPermission ->
				vpnController.updateUiState(
					hasPermission
				)
			},
			onRegistrationUpdateRequired = {
				(application as DnsToggleApplication).updateWifiMonitoringRegistration()
			}
		)

		initControllers()
		setupWindowInsets()
		setupToolbar()
		initializeViews()
		observeViewModel()

		setupGlobalInteractions()
		setupStatusBarColorSync()
		updateMainPermissionUiState()
		ssidController.updateUiState(PermissionHelper.hasSsidPermissions(this))

		if (!PermissionHelper.hasSecureSettingsPermission(this)) {
			showInitialPermissionDialog()
		}

		startPermissionPolling()
		handleIntentExtras(intent)

		mainScrollView.viewTreeObserver.addOnGlobalFocusChangeListener(focusChangeListener)

		setupScrollSpring()

		// TEMPORARY BETA INFO
		_InfoNoticeHelper.showOnceOnStartup(this)
		val contentWrapper =
			findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.contentWrapper)
		_InfoNoticeHelper.injectBetaFeedbackButton(
			this,
			contentWrapper,
			R.id.cardMainPermissionLayout
		)
		// TEMPORARY BETA INFO - END
	}

	private fun setupScrollSpring() {
		scrollSpring = SpringAnimation(mainScrollView, DynamicAnimation.SCROLL_Y).apply {
			spring = SpringForce().apply {
				stiffness = SpringForce.STIFFNESS_MEDIUM
				dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
			}
			addUpdateListener { _, value, _ ->
				val delta = (value - lastSpringValue).toInt()
				lastSpringValue = value
				if (delta != 0) {
					mainScrollView.dispatchNestedScroll(
						0, delta, 0, 0, null, ViewCompat.TYPE_NON_TOUCH
					)
				}
			}
		}
	}

	override fun onDestroy() {
		mainScrollView.viewTreeObserver.removeOnGlobalFocusChangeListener(
			focusChangeListener
		)
		super.onDestroy()
	}

	private fun initControllers() {
		dnsController = DnsSectionController(
			activity = this,
			viewModel = dnsViewModel,
			onShowInitialPermissionDialog = { showInitialPermissionDialog() },
			onRequestTileUpdate = { requestTileUpdate() }
		)

		ssidController = SsidSectionController(
			activity = this,
			viewModel = dnsViewModel,
			permissionHandler = permissionHandler,
			onRequestIgnoreBattery = { requestIgnoreBatteryOptimizations() }
		)

		vpnController = VpnSectionController(
			activity = this,
			viewModel = dnsViewModel,
			permissionHandler = permissionHandler,
			onShowWifiMonitoringInfoDialog = { showWifiMonitoringInfoDialog() }
		)

		backupController = BackupController(
			activity = this,
			viewModel = dnsViewModel,
			onUpdateToolbarTitle = { updateToolbarTitle() }
		)

		miscController = MiscSettingsController(
			activity = this,
			viewModel = dnsViewModel,
			onOpenUrl = { openUrl(it) },
			onUsbToggleVisibilityChanged = {
				mainScrollView.viewTreeObserver.addOnPreDrawListener(object :
					ViewTreeObserver.OnPreDrawListener {
					override fun onPreDraw(): Boolean {
						mainScrollView.viewTreeObserver.removeOnPreDrawListener(this)
						mainScrollView.fullScroll(View.FOCUS_DOWN)
						return true
					}
				})
			}
		)
	}

	override fun onResume() {
		super.onResume()
		dnsViewModel.loadSettings()
		dnsViewModel.refreshCurrentSsid()
		updateMainPermissionUiState()
		ssidController.updateUiState(PermissionHelper.hasSsidPermissions(this))
		vpnController.updateUiState(PermissionHelper.hasNotificationPermission(this))
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntentExtras(intent)
	}

	private fun handleIntentExtras(intent: Intent?) {
		if (intent?.getBooleanExtra("show_permission_dialog", false) == true &&
			!PermissionHelper.hasSecureSettingsPermission(this)
		) {
			showInitialPermissionDialog()
		}

		if (intent?.getBooleanExtra(EXTRA_FOCUS_DNS_INPUT, false) == true) {
			val enableAfterSave = intent.getBooleanExtra(EXTRA_ENABLE_DNS_AFTER_SAVE, false)
			dnsController.showAddHostnameDialog(enableAfterSave = enableAfterSave)
		}

		if (intent?.action == Intent.ACTION_VIEW) {
			intent.data?.let { backupController.processImportUri(it) }
		}
	}

	private fun setupWindowInsets() {
		val mainView = findViewById<View>(R.id.main)
		val statusBarBackground = findViewById<View>(R.id.statusBarBackground)
		val appBarLayout = findViewById<AppBarLayout>(R.id.appBarLayout)
		val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
		val contentWrapper = findViewById<View>(R.id.contentWrapper)
		val scrollInnerContainer = (contentWrapper.parent as View)

		val density = resources.displayMetrics.density
		val defaultPadding = (12 * density).toInt()

		ViewCompat.setOnApplyWindowInsetsListener(mainView) { _, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

			val left = systemBars.left.coerceAtLeast(displayCutout.left)
			val right = systemBars.right.coerceAtLeast(displayCutout.right)

			val statusParams = statusBarBackground.layoutParams
			statusParams.height = systemBars.top
			statusBarBackground.layoutParams = statusParams

			appBarLayout.setPadding(0, systemBars.top, 0, 0)
			toolbar.setPadding(left, 0, right, 0)

			contentWrapper.setPadding(left, 0, right, 0)

			val scrollParams =
				mainScrollView.layoutParams as android.view.ViewGroup.MarginLayoutParams
			scrollParams.bottomMargin = systemBars.bottom
			mainScrollView.layoutParams = scrollParams

			scrollInnerContainer.setPadding(
				defaultPadding,
				defaultPadding,
				defaultPadding,
				defaultPadding
			)

			insets
		}
	}

	private fun setupToolbar() {
		val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
		setSupportActionBar(toolbar)

		val accentColor = MaterialColors.getColor(toolbar, android.R.attr.colorPrimary)
		toolbar.logo?.setTint(accentColor)

		// Hide toolbar on scroll for height-constrained device
		val isHeightConstrained = resources.configuration.screenHeightDp < 600
		if (isHeightConstrained) {
			val params = toolbar.layoutParams as AppBarLayout.LayoutParams
			params.scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
					AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
					AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
			toolbar.layoutParams = params
		}
	}

	private fun initializeViews() {
		mainScrollView = findViewById(R.id.mainScrollView)
		btnMenu = findViewById(R.id.btnMenu)
		cardMainPermission = findViewById(R.id.cardMainPermissionLayout)
		btnFixMainPermission = findViewById(R.id.btnFixMainPermission)
		cardOverrideStatus = findViewById(R.id.cardOverrideStatus)
		tvOverrideStatus = findViewById(R.id.tvOverrideStatus)

		dnsController.initialize(
			rowPrivateDns = findViewById(R.id.rowPrivateDns),
			dnsToggleSwitch = findViewById(R.id.switchPrivateDns),
			addHostnameButton = findViewById(R.id.btnAddHostname),
			dnsHostnameListContainer = findViewById(R.id.dnsHostnameListContainer),
			rowDisableDnsTest = findViewById(R.id.rowDisableDnsTest),
			switchDisableDnsTest = findViewById(R.id.switchDisableDnsTest)
		)

		ssidController.initialize(
			btnSsidInfo = findViewById(R.id.btnSsidInfo),
			addSsidButton = findViewById(R.id.btnAddSsid),
			tvWifiProfilesTitle = findViewById(R.id.tvWifiProfilesTitle),
			permissionNoticeText = findViewById(R.id.tvPermissionNotice),
			btnGrantPermission = findViewById(R.id.btnGrantPermission),
			dividerSsidList = findViewById(R.id.dividerSsidList),
			ssidListContainer = findViewById(R.id.ssidListContainer),
			dividerSsidSettings = findViewById(R.id.dividerSsidSettings),
			rowAutoSaveState = findViewById(R.id.rowAutoSaveState),
			switchAutoSaveState = findViewById(R.id.switchAutoSaveState),
			rowAutoSaveHost = findViewById(R.id.rowAutoSaveHost),
			switchAutoSaveHost = findViewById(R.id.switchAutoSaveHost),
			rowConnectivityWatchdogToggle = findViewById(R.id.rowConnectivityWatchdogToggle),
			switchConnectivityWatchdog = findViewById(R.id.switchConnectivityWatchdog),
			rowConnectivityWatchdogDebounce = findViewById(R.id.rowConnectivityWatchdogDebounce),
			tvConnectivityWatchdogDebounceValue = findViewById(R.id.tvConnectivityWatchdogDebounceValue),
			rowConnectivityWatchdogTargets = findViewById(R.id.rowConnectivityWatchdogTargets),
			tvConnectivityWatchdogTargetsValue = findViewById(R.id.tvConnectivityWatchdogTargetsValue)
		)

		vpnController.initialize(
			btnVpnInfo = findViewById(R.id.btnVpnInfo),
			rowVpnOverrideToggle = findViewById(R.id.rowVpnOverrideToggle),
			switchVpnOverride = findViewById(R.id.switchVpnOverride),
			rowVpnDns = findViewById(R.id.rowVpnDns),
			tvVpnDnsValue = findViewById(R.id.tvVpnDnsValue),
			vpnPermissionNoticeText = findViewById(R.id.tvVpnPermissionNotice),
			btnGrantVpnPermission = findViewById(R.id.btnGrantVpnPermission)
		)

		miscController.initialize(
			switchShowToast = findViewById(R.id.switchShowToast),
			rowShowToast = findViewById(R.id.rowShowToast),
			switchHideLauncher = findViewById(R.id.switchHideLauncher),
			rowHideLauncher = findViewById(R.id.rowHideLauncher),
			rowUsbDebuggingTile = findViewById(R.id.rowUsbDebuggingTileLayout),
			switchUsbDebuggingTile = findViewById(R.id.switchUsbDebuggingTile),
			tvUsbDebuggingTileSummary = findViewById(R.id.tvUsbDebuggingTileSummary)
		)

		findViewById<TextView>(R.id.tvToggleLabel).text = getString(R.string.private_dns)

		val tvAppVersion = findViewById<TextView>(R.id.tvAppVersion)
		try {
			val pInfo = packageManager.getPackageInfo(packageName, 0)
			tvAppVersion.text = getString(R.string.app_version_format, pInfo.versionName)
		} catch (_: Exception) {
			tvAppVersion.visibility = View.GONE
		}
	}

	private fun observeViewModel() {
		dnsController.observeViewModel()
		ssidController.observeViewModel()
		vpnController.observeViewModel()
		miscController.observeViewModel()

		dnsViewModel.isInVpnOverride.observe(this) { _ -> updateOverrideStatusUi() }
		dnsViewModel.activeSsidOverride.observe(this) { _ -> updateOverrideStatusUi() }
		dnsViewModel.currentSsid.observe(this) { _ -> updateOverrideStatusUi() }
		dnsViewModel.networkProfiles.observe(this) { _ -> updateOverrideStatusUi() }

		dnsViewModel.hasPermissionError.observe(this) { hasError ->
			if (hasError == true) showInitialPermissionDialog()
		}

		dnsViewModel.isKeyInvalidated.observe(this) { invalidated ->
			if (invalidated == true) showKeyInvalidatedDialog()
		}

		updateToolbarTitle()
	}

	private fun setupGlobalInteractions() {
		btnMenu.setOnClickListener { backupController.showMenuBottomSheet() }

		btnFixMainPermission.setOnClickListener {
			showInitialPermissionDialog()
		}
	}

	private fun setupStatusBarColorSync() {
		val statusBarBackground = findViewById<View>(R.id.statusBarBackground)
		val mainScrollView = findViewById<NestedScrollView>(R.id.mainScrollView)

		val isHeightConstrained = resources.configuration.screenHeightDp < 600
		if (!isHeightConstrained) {
			statusBarBackground.visibility = View.GONE
			return
		}

		statusBarBackground.visibility = View.VISIBLE

		val baseColor = MaterialColors.getColor(this, MaterialR.attr.colorSurface, Color.BLACK)
		val liftedColor =
			MaterialColors.getColor(this, MaterialR.attr.colorSurfaceContainer, Color.BLACK)

		statusBarBackground.setBackgroundColor(baseColor)

		var animator: android.animation.ValueAnimator? = null

		mainScrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
			val isLifted = scrollY > 0
			val target = if (isLifted) liftedColor else baseColor

			if (statusBarBackground.tag != target) {
				statusBarBackground.tag = target
				animator?.cancel()

				val startColor = if (isLifted) baseColor else liftedColor

				animator = android.animation.ValueAnimator.ofArgb(startColor, target).apply {
					duration = 150
					addUpdateListener { statusBarBackground.setBackgroundColor(it.animatedValue as Int) }
					start()
				}
			}
		})
	}

	private fun updateOverrideStatusUi() {
		val vpnActive = dnsViewModel.isInVpnOverride.value == true
		val activeSsidOverride = dnsViewModel.activeSsidOverride.value
		val currentSsid = dnsViewModel.currentSsid.value
		val profiles = dnsViewModel.networkProfiles.value ?: emptyList()
		val currentProfile = profiles.find { it.ssid == currentSsid }

		when {
			vpnActive -> {
				cardOverrideStatus.visibility = View.VISIBLE
				tvOverrideStatus.text = getString(R.string.status_override_vpn)
			}

			currentProfile != null && (!currentProfile.isEnabled || currentProfile.targetHostname != null) -> {
				cardOverrideStatus.visibility = View.VISIBLE
				val state = if (currentProfile.isEnabled) {
					currentProfile.targetHostname ?: getString(R.string.default_dns_label)
				} else {
					getString(R.string.off_label)
				}
				tvOverrideStatus.text =
					getString(R.string.status_override_ssid_profile, currentSsid, state)
			}

			activeSsidOverride != null -> {
				cardOverrideStatus.visibility = View.VISIBLE
				tvOverrideStatus.text = getString(R.string.status_override_ssid, activeSsidOverride)
			}

			else -> cardOverrideStatus.visibility = View.GONE
		}
	}

	private fun updateMainPermissionUiState() {
		if (PermissionHelper.hasSecureSettingsPermission(this)) {
			if (::cardMainPermission.isInitialized) cardMainPermission.visibility = View.GONE
		} else {
			cardMainPermission.visibility = View.VISIBLE
		}
	}

	private fun startPermissionPolling() {
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				while (true) {
					if (!PermissionHelper.hasSecureSettingsPermission(this@MainActivity)) {
						delay(2000.milliseconds)
					} else {
						updateMainPermissionUiState()
						if (permissionDialog?.isShowing == true) {
							permissionDialog?.dismiss()
							Toast.makeText(
								this@MainActivity,
								R.string.permission_granted,
								Toast.LENGTH_SHORT
							).show()
						}
						break
					}
				}
			}
		}
	}

	private fun showWifiMonitoringInfoDialog() {
		val powerManager = getSystemService(POWER_SERVICE) as PowerManager
		val isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(packageName)

		SsidDialogHelper.showWifiMonitoringInfo(this, isIgnoringBattery) {
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

		try {
			val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
				data = "package:$packageName".toUri()
			}
			startActivity(intent)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to request ignore battery optimizations", e)
			startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
		}
	}

	private fun updateToolbarTitle() {
		val sharedPreferences = (application as DnsToggleApplication).getPrefs()
		supportActionBar?.title =
			sharedPreferences.getString(
				Constants.PREF_DYNAMIC_APP_NAME,
				getString(R.string.app_name)
			)
	}

	private fun requestTileUpdate() {
		TileServiceCompat.requestListeningState(
			this,
			ComponentName(this, DnsToggleService::class.java)
		)
	}

	private fun showKeyInvalidatedDialog() {
		CommonDialogHelper.showKeyInvalidatedDialog(this) { dnsViewModel.dismissKeyInvalidatedAlert() }
	}

	private fun showInitialPermissionDialog() {
		if (permissionDialog?.isShowing == true) return
		permissionDialog = PermissionDialogHelper.showSecureSettingsPermissionDialog(
			context = this,
			packageName = packageName,
			onCopyCommand = { adbCommand ->
				val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
				clipboard.setPrimaryClip(
					ClipData.newPlainText(
						getString(R.string.adb_command_label),
						adbCommand
					)
				)
				Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show()
			},
			onAttemptElevatedGrant = { dialog, view ->
				val progress = view.findViewById<View>(R.id.progressElevatedGrant)
				val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
				val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

				Toast.makeText(
					this, when {
						ShizukuUtils.isAvailable() -> R.string.toast_attempting_shizuku
						RootUtils.isAvailable() -> R.string.toast_attempting_root
						else -> R.string.toast_attempting_fallback
					}, Toast.LENGTH_SHORT
				).show()

				progress.visibility = View.VISIBLE
				positiveButton.isEnabled = false
				negativeButton.isEnabled = false

				lifecycleScope.launch {
					val startTime = System.currentTimeMillis()
					val success = attemptSecureSettingsGrant(this@MainActivity, packageName)

					// Artificial delay of 5s to ensure toasts are visible and indicate work
					val elapsedTime = System.currentTimeMillis() - startTime
					if (elapsedTime < 5000) delay((5000L - elapsedTime).milliseconds)

					updateMainPermissionUiState()

					if (PermissionHelper.hasSecureSettingsPermission(this@MainActivity)) {
						if (success) {
							Toast.makeText(
								this@MainActivity,
								R.string.permission_granted,
								Toast.LENGTH_SHORT
							).show()
						}
						dialog.dismiss()
					} else {
						Toast.makeText(this@MainActivity, R.string.grant_failed, Toast.LENGTH_SHORT)
							.show()
						progress.visibility = View.GONE
						positiveButton.isEnabled = true
						negativeButton.isEnabled = true
					}
				}
			}
		)
	}

	private fun openUrl(url: String) {
		try {
			startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
		} catch (e: Exception) {
			Log.e(TAG, "Failed to open URL", e)
		}
	}

	private fun smoothScrollToCenter(view: View) {
		val contentChild = mainScrollView.getChildAt(0) ?: return
		val appBarLayout = findViewById<AppBarLayout>(R.id.appBarLayout)

		var isDescendant = false
		var p = view.parent
		while (p != null) {
			if (p == mainScrollView) {
				isDescendant = true
				break
			}
			p = p.parent
		}
		if (!isDescendant) return

		val rect = android.graphics.Rect()
		view.getDrawingRect(rect)
		(contentChild as? android.view.ViewGroup)?.offsetDescendantRectToMyCoords(view, rect)

		val absoluteTop = rect.top
		val viewHeight = view.height
		val scrollViewHeight = mainScrollView.height

		val targetY = absoluteTop - (scrollViewHeight / 2) + (viewHeight / 2)

		if (targetY > (appBarLayout.height / 2)) {
			appBarLayout.setExpanded(false, true)
		} else if (targetY <= 0) {
			appBarLayout.setExpanded(true, true)
		}

		lastSpringValue = mainScrollView.scrollY.toFloat()
		scrollSpring?.animateToFinalPosition(targetY.coerceAtLeast(0).toFloat())
	}
}
