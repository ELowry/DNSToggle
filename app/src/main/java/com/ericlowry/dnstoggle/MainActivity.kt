package com.ericlowry.dnstoggle

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

	private lateinit var tvToggleLabel: TextView
	private lateinit var switchDns: SwitchMaterial
	private lateinit var etCustomDns: TextInputEditText
	private val handler = Handler(Looper.getMainLooper())
	private var updateRunnable: Runnable? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		val mainView = findViewById<android.view.View>(R.id.main)
		ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
			insets
		}

		val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
		setSupportActionBar(toolbar)

		tvToggleLabel = findViewById(R.id.tvToggleLabel)
		switchDns = findViewById(R.id.switchDns)
		etCustomDns = findViewById(R.id.etCustomDns)

		// Ensure the label always reads "Private DNS"
		tvToggleLabel.text = getString(R.string.private_dns)

		if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
			showPermissionDialog()
		} else {
			initUi()
		}
	}

	private fun initUi() {
		val resolver = contentResolver
		try {
			val currentMode = Settings.Global.getString(resolver, "private_dns_mode")
			val currentSpecifier = Settings.Global.getString(resolver, "private_dns_specifier")

			val isEnabled = currentMode == "hostname"
			switchDns.isChecked = isEnabled
			etCustomDns.setText(currentSpecifier ?: "")
			updateTitle()

			switchDns.setOnCheckedChangeListener { _, isChecked ->
				val newMode = if (isChecked) "hostname" else "opportunistic"
				try {
					Settings.Global.putString(resolver, "private_dns_mode", newMode)
					requestTileUpdate()
				} catch (e: SecurityException) {
					e.printStackTrace()
				}
			}

			etCustomDns.doAfterTextChanged { text ->
				updateRunnable?.let { handler.removeCallbacks(it) }
				updateRunnable = Runnable {
					val newDns = text.toString().trim()
					try {
						Settings.Global.putString(resolver, "private_dns_specifier", newDns)
					} catch (e: SecurityException) {
						e.printStackTrace()
					}
				}
				handler.postDelayed(updateRunnable!!, 500)
			}
		} catch (e: SecurityException) {
			e.printStackTrace()
		}
	}

	private fun updateTitle() {
		val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
		val dynamicName = prefs.getString("dynamic_app_name", getString(R.string.app_name))
		supportActionBar?.title = dynamicName
	}

	private fun requestTileUpdate() {
		TileServiceCompat.requestListeningState(this, ComponentName(this, DnsToggleService::class.java))
	}

	private fun showPermissionDialog() {
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
				showRenameDialog()
				true
			}
			else -> super.onOptionsItemSelected(item)
		}
	}

	private fun showRenameDialog() {
		val builder = AlertDialog.Builder(this)
		builder.setTitle(getString(R.string.rename_app))

		val input = EditText(this)
		val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
		val currentName = prefs.getString("dynamic_app_name", getString(R.string.app_name))
		input.setText(currentName)

		val padding = (24 * resources.displayMetrics.density).toInt()
		val container = FrameLayout(this)
		container.setPadding(padding, padding / 4, padding, 0)
		container.addView(input)
		builder.setView(container)

		builder.setPositiveButton(getString(R.string.ok)) { dialog, _ ->
			val newName = input.text.toString().trim()
			if (newName.isNotEmpty()) {
				prefs.edit { putString("dynamic_app_name", newName) }
				updateTitle()
				requestTileUpdate()
			}
			dialog.dismiss()
		}
		builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }

		builder.show()
	}
}

object TileServiceCompat {
	fun requestListeningState(context: Context, componentName: ComponentName) {
		android.service.quicksettings.TileService.requestListeningState(context, componentName)
	}
}
