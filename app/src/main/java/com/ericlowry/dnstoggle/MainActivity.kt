package com.ericlowry.dnstoggle

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var dnsViewModel: DnsViewModel

    private lateinit var privateDnsLabel: TextView
    private lateinit var dnsToggleSwitch: MaterialSwitch
    private lateinit var customDnsInput: TextInputEditText
    
    private lateinit var switchAutoBlacklist: MaterialSwitch
    private lateinit var switchAutoWhitelist: MaterialSwitch
    private lateinit var permissionNoticeText: TextView
    private lateinit var btnGrantPermission: Button
    private lateinit var ssidListContainer: LinearLayout
    private lateinit var addSsidButton: ImageButton
    private lateinit var btnSsidInfo: ImageButton
    private lateinit var dividerSsidList: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private var inputUpdateRunnable: Runnable? = null

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

        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            showInitialPermissionDialog()
        } else {
            setupUserInteractions()
            updateSsidUiState(hasSsidPermissions())
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
        customDnsInput = findViewById(R.id.inputCustomDns)

        switchAutoBlacklist = findViewById(R.id.switchAutoBlacklist)
        switchAutoWhitelist = findViewById(R.id.switchAutoWhitelist)
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
            dnsToggleSwitch.isChecked = (mode == "hostname")
        }

        dnsViewModel.privateDnsSpecifier.observe(this) { specifier ->
            if (customDnsInput.text.toString() != specifier) {
                customDnsInput.setText(specifier ?: "")
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

        updateToolbarTitle()
    }

    private fun setupUserInteractions() {
        dnsToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            dnsViewModel.togglePrivateDns(isChecked)
            requestTileUpdate()
        }

        customDnsInput.doAfterTextChanged { text ->
            inputUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
            inputUpdateRunnable = Runnable {
                dnsViewModel.updateCustomDns(text.toString().trim())
            }
            mainHandler.postDelayed(inputUpdateRunnable!!, 500)
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
            val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val alreadyHandled = sharedPreferences.getBoolean("notif_permission_handled", false)
            val isGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            
            if (!isGranted && !alreadyHandled) {
                showNotificationPermissionRationale()
            }
        }
    }

    private fun showNotificationPermissionRationale() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        
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
        } catch (ignored: Exception) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun updateToolbarTitle() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val dynamicName = sharedPreferences.getString("dynamic_app_name", getString(R.string.app_name))
        supportActionBar?.title = dynamicName
    }

    private fun requestTileUpdate() {
        TileServiceCompat.requestListeningState(this, ComponentName(this, DnsToggleService::class.java))
    }

    private fun showInitialPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setMessage(getString(R.string.permission_message))
            .setPositiveButton(getString(R.string.ok)) { _, _ -> finish() }
            .setCancelable(false)
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
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
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
