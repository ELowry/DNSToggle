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
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope

import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

import kotlinx.coroutines.Job
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
    private lateinit var tilCustomDns: com.google.android.material.textfield.TextInputLayout
    private lateinit var customDnsInput: TextInputEditText
    
    private lateinit var switchAutoBlacklist: MaterialSwitch
    private lateinit var switchAutoWhitelist: MaterialSwitch
    private lateinit var switchHideLauncher: MaterialSwitch
    private lateinit var permissionNoticeText: TextView
    private lateinit var btnGrantPermission: Button
    private lateinit var ssidListContainer: LinearLayout
    private lateinit var addSsidButton: ImageButton
    private lateinit var btnSsidInfo: ImageButton
    private lateinit var dividerSsidList: View

    private var inputUpdateJob: Job? = null
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
        checkNotificationPermission()
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
        updateSsidUiState(hasSsidPermissions())
        handleIntentExtras(intent)

        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch {
                val wasGrantSuccessful = RootUtils.grantSecureSettingsPermission(packageName)
                if (!wasGrantSuccessful && intent.getBooleanExtra("show_permission_dialog", false)) {
                    showInitialPermissionDialog()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FOCUS_DNS_INPUT, false) == true) {
            isRedirectedFromTile = true
            customDnsInput.requestFocus()
            customDnsInput.postDelayed({
                updateDnsErrorState(isEnabled = true, customDnsInput.text.toString())
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(customDnsInput, 0)
            }, 200)
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
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)
    }

    private fun initializeViews() {
        privateDnsLabel = findViewById(R.id.tvToggleLabel)
        dnsToggleSwitch = findViewById(R.id.switchPrivateDns)
        progressRootAction = findViewById(R.id.progressRootAction)
        tilCustomDns = findViewById(R.id.tilCustomDns)
        customDnsInput = findViewById(R.id.inputCustomDns)

        switchAutoBlacklist = findViewById(R.id.switchAutoBlacklist)
        switchAutoWhitelist = findViewById(R.id.switchAutoWhitelist)
        switchHideLauncher = findViewById(R.id.switchHideLauncher)
        permissionNoticeText = findViewById(R.id.tvPermissionNotice)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        ssidListContainer = findViewById(R.id.ssidListContainer)
        addSsidButton = findViewById(R.id.btnAddSsid)
        btnSsidInfo = findViewById(R.id.btnSsidInfo)
        dividerSsidList = findViewById(R.id.dividerSsidList)

        privateDnsLabel.text = getString(R.string.private_dns)
    }

    private fun observeViewModel() {
        dnsViewModel.privateDnsMode.observe(this) { mode ->
            val isEnabled = (mode == "hostname")
            dnsToggleSwitch.isChecked = isEnabled
            updateDnsErrorState(isEnabled, customDnsInput.text.toString())
        }

        dnsViewModel.privateDnsSpecifier.observe(this) { specifier ->
            if (customDnsInput.text.toString() != specifier) {
                customDnsInput.setText(specifier ?: "")
                updateDnsErrorState(dnsToggleSwitch.isChecked, specifier ?: "")
            }
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

        dnsViewModel.hideLauncherIcon.observe(this) { isHidden ->
            switchHideLauncher.isChecked = isHidden
            updateLauncherComponentState(isHidden)
        }

        dnsViewModel.dnsReachability.observe(this) { state ->
            updateDnsErrorState(dnsToggleSwitch.isChecked, customDnsInput.text.toString(), state)
        }

        dnsViewModel.hasPermissionError.observe(this) { hasError ->
            if (hasError) {
                showInitialPermissionDialog()
            }
        }

        updateToolbarTitle()
    }

    private fun setupUserInteractions() {
        dnsToggleSwitch.setOnClickListener {
            val isChecked = dnsToggleSwitch.isChecked
            val address = customDnsInput.text.toString().trim()
            
            updateDnsErrorState(isChecked, address)
            
            if (isChecked && (address.isEmpty() || !NetworkUtils.isValidDnsHostname(address))) {
                // Don't allow toggling on if invalid or empty
                dnsToggleSwitch.isChecked = false
                return@setOnClickListener
            }

            if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                dnsViewModel.togglePrivateDns(isChecked)
                requestTileUpdate()
            } else {
                // Attempt root grant again
                setLoadingState(true)
                lifecycleScope.launch {
                    val success = RootUtils.grantSecureSettingsPermission(packageName)
                    setLoadingState(false)
                    if (success) {
                        dnsViewModel.togglePrivateDns(isChecked)
                        requestTileUpdate()
                    } else {
                        // Revert UI and show manual instructions
                        dnsToggleSwitch.isChecked = !isChecked
                        updateDnsErrorState(dnsToggleSwitch.isChecked, address)
                        showInitialPermissionDialog()
                    }
                }
            }
        }

        customDnsInput.doAfterTextChanged { text ->
            val address = text.toString().trim()
            updateDnsErrorState(dnsToggleSwitch.isChecked, address)
            inputUpdateJob?.cancel()
            inputUpdateJob = lifecycleScope.launch {
                delay(500.milliseconds)
                dnsViewModel.updateCustomDns(address)
            }
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
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        foregroundPermissionLauncher.launch(permissions)
    }

    private fun showBackgroundLocationRationale() {
        AlertDialog.Builder(this)
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
            val isGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            
            if (!isGranted && !alreadyHandled) {
                showNotificationPermissionRationale()
            }
        }
    }

    private fun showNotificationPermissionRationale() {
        val sharedPreferences = (application as DnsToggleApplication).getPrefs()
        
        AlertDialog.Builder(this)
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
            itemView.findViewById<View>(R.id.btnEditSsid).setOnClickListener { showAddSsidDialog(ssid) }
            itemView.findViewById<View>(R.id.btnDeleteSsid).setOnClickListener {
                showDeleteConfirmDialog(ssid)
            }
            ssidListContainer.addView(itemView)
        }
    }

    private fun showAddSsidDialog(existingSsid: String? = null) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (existingSsid == null) getString(R.string.add_ssid) else getString(R.string.edit_ssid))

        val inputTextField = EditText(this)
        inputTextField.hint = getString(R.string.ssid_hint)
        if (existingSsid != null) {
            inputTextField.setText(existingSsid)
        } else {
            val currentSsid = NetworkUtils.getCurrentWifiSsid(this)
            val blacklist = dnsViewModel.ssidBlacklist.value ?: emptySet()
            if (currentSsid != null && !blacklist.contains(currentSsid)) {
                inputTextField.setText(currentSsid)
            }
        }

        val sidePadding = (24 * resources.displayMetrics.density).toInt()
        val topPadding = sidePadding / 4
        val containerFrame = FrameLayout(this)
        containerFrame.setPadding(sidePadding, topPadding, sidePadding, 0)
        containerFrame.addView(inputTextField)
        builder.setView(containerFrame)

        builder.setPositiveButton(getString(R.string.ok)) { _, _ ->
            val newSsidName = inputTextField.text.toString().trim().removePrefix("\"").removeSuffix("\"")
            if (newSsidName.isNotEmpty()) {
                if (existingSsid != null) {
                    dnsViewModel.updateSsidInBlacklist(existingSsid, newSsidName)
                } else {
                    dnsViewModel.addToBlacklist(newSsidName)
                }
            }
        }
        builder.setNegativeButton(getString(R.string.cancel), null)
        builder.show()
    }

    private fun showDeleteConfirmDialog(ssidToDelete: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_ssid_confirm))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                dnsViewModel.removeFromBlacklist(ssidToDelete)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showWifiMonitoringInfoDialog() {
        val message = StringBuilder(getString(R.string.wifi_monitoring_info_text))
        
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(packageName)
        
        if (!isIgnoringBattery) {
            message.append("\n\n").append(getString(R.string.battery_optimization_explanation))
        }

        val builder = AlertDialog.Builder(this)
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
        val dynamicName = sharedPreferences.getString("dynamic_app_name", getString(R.string.app_name))
        supportActionBar?.title = dynamicName
    }

    private fun updateDnsErrorState(
        isEnabled: Boolean,
        address: String,
        reachability: DnsViewModel.ReachabilityState = dnsViewModel.dnsReachability.value ?: DnsViewModel.ReachabilityState.IDLE
    ) {
        val effectivelyEnabled = isEnabled || (isRedirectedFromTile && address.isEmpty())
        
        when {
            effectivelyEnabled && address.isEmpty() -> {
                tilCustomDns.error = getString(R.string.error_empty_dns_host)
                tilCustomDns.helperText = null
            }
            address.isNotEmpty() && !NetworkUtils.isValidDnsHostname(address) -> {
                tilCustomDns.error = getString(R.string.error_invalid_dns_host)
                tilCustomDns.helperText = null
            }
            reachability == DnsViewModel.ReachabilityState.UNREACHABLE -> {
                tilCustomDns.error = getString(R.string.warning_unreachable_dns)
                tilCustomDns.helperText = null
            }
            reachability == DnsViewModel.ReachabilityState.TESTING -> {
                tilCustomDns.error = null
                tilCustomDns.helperText = getString(R.string.status_testing_dns)
            }
            else -> {
                tilCustomDns.error = null
                tilCustomDns.helperText = null
            }
        }
    }

    private fun requestTileUpdate() {
        TileServiceCompat.requestListeningState(this, ComponentName(this, DnsToggleService::class.java))
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

    private fun showInitialPermissionDialog() {
        val adbCommand = "adb shell pm grant com.ericlowry.dnstoggle android.permission.WRITE_SECURE_SETTINGS"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setMessage(getString(R.string.permission_message))
            .setPositiveButton(getString(R.string.copy_command)) { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ADB Command", adbCommand)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.command_copied), Toast.LENGTH_SHORT).show()
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
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.rename_app))

        val inputTextField = EditText(this)
        val sharedPreferences = (application as DnsToggleApplication).getPrefs()
        val currentAppName = sharedPreferences.getString("dynamic_app_name", getString(R.string.app_name))
        inputTextField.setText(currentAppName)
        
        val sidePadding = (24 * resources.displayMetrics.density).toInt()
        val topPadding = sidePadding / 4
        val containerFrame = FrameLayout(this)
        containerFrame.setPadding(sidePadding, topPadding, sidePadding, 0)
        containerFrame.addView(inputTextField)
        builder.setView(containerFrame)

        builder.setPositiveButton(getString(R.string.ok)) { dialog, _ ->
            val newAppName = inputTextField.text.toString().trim()
            if (newAppName.isNotEmpty()) {
                sharedPreferences.edit { putString("dynamic_app_name", newAppName) }
                updateToolbarTitle()
                requestTileUpdate()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }

        builder.show()
    }
}
