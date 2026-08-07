package com.ericlowry.dnstoggle.ui.controller

import android.content.ComponentName
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.data.repository.DnsSettingsRepository
import com.ericlowry.dnstoggle.service.DnsToggleService
import com.ericlowry.dnstoggle.service.TileServiceCompat
import com.ericlowry.dnstoggle.ui.dialog.BackupDialogHelper
import com.ericlowry.dnstoggle.util.BackupManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupController(
	private val activity: ComponentActivity,
	private val viewModel: DnsViewModel,
	private val onUpdateToolbarTitle: () -> Unit
) {
	companion object {
		private const val TAG = "BackupController"
	}

	private val exportLauncher: ActivityResultLauncher<String> =
		activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
			uri?.let { targetUri ->
				BackupDialogHelper.showPasswordDialog(
					activity,
					R.string.export_config,
					R.string.export,
					R.string.export_password_description,
					onCancel = {
						try {
							android.provider.DocumentsContract.deleteDocument(
								activity.contentResolver,
								targetUri
							)
						} catch (e: Exception) {
							Log.e(TAG, "Failed to delete empty export file", e)
						}
					}
				) { password ->
					activity.lifecycleScope.launch(Dispatchers.IO) {
						try {
							val rawJson = DnsSettingsRepository.exportConfigToJson()
							val encrypted = BackupManager.encryptBackup(rawJson, password)
							activity.contentResolver.openOutputStream(targetUri)?.use { out ->
								out.write(encrypted.toByteArray())
							}
							withContext(Dispatchers.Main) {
								Toast.makeText(
									activity,
									R.string.export_success,
									Toast.LENGTH_SHORT,
								).show()
							}
						} catch (_: Exception) {
							withContext(Dispatchers.Main) {
								Toast.makeText(
									activity,
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
		activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			uri?.let { processImportUri(it) }
		}

	fun startExport() {
		exportLauncher.launch("DNSToggle_Backup.dnstoggle")
	}

	fun startImport() {
		importLauncher.launch(arrayOf("*/*"))
	}

	fun processImportUri(uri: Uri) {
		BackupDialogHelper.showPasswordDialog(
			activity,
			R.string.import_config,
			R.string.import_action
		) { password ->
			activity.lifecycleScope.launch(Dispatchers.IO) {
				try {
					val encryptedData =
						activity.contentResolver.openInputStream(uri)?.bufferedReader()
							.use { reader ->
								reader?.readText()
							} ?: return@launch

					val decryptedJson = BackupManager.decryptBackup(encryptedData, password)

					withContext(Dispatchers.Main) {
						if (decryptedJson != null) {
							BackupDialogHelper.showImportConfirmationDialog(activity) {
								val isValidJson =
									DnsSettingsRepository.importConfigFromJson(decryptedJson)
								if (isValidJson) {
									viewModel.loadSettings()
									Toast.makeText(
										activity,
										R.string.import_success,
										Toast.LENGTH_SHORT,
									).show()
								} else {
									Toast.makeText(
										activity,
										R.string.import_failed,
										Toast.LENGTH_SHORT,
									).show()
								}
							}
						} else {
							Toast.makeText(
								activity,
								R.string.import_failed_password,
								Toast.LENGTH_SHORT,
							).show()
						}
					}
				} catch (_: Exception) {
					withContext(Dispatchers.Main) {
						Toast.makeText(
							activity,
							R.string.import_failed,
							Toast.LENGTH_SHORT,
						).show()
					}
				}
			}
		}
	}

	fun showRenameAppDialog() {
		val sharedPreferences = (activity.application as DnsToggleApplication).getPrefs()
		val currentAppName =
			sharedPreferences.getString(
				Constants.PREF_DYNAMIC_APP_NAME,
				activity.getString(R.string.app_name)
			)

		BackupDialogHelper.showRenameAppDialog(activity, currentAppName) { newAppName ->
			sharedPreferences.edit { putString(Constants.PREF_DYNAMIC_APP_NAME, newAppName) }
			onUpdateToolbarTitle()
			requestTileUpdate()
		}
	}

	fun showMenuBottomSheet() {
		val bottomSheet = BottomSheetDialog(activity)
		val view = activity.layoutInflater.inflate(
			R.layout.dialog_menu_bottom_sheet,
			activity.findViewById(android.R.id.content),
			false
		)
		bottomSheet.setContentView(view)

		bottomSheet.setOnShowListener { dialog ->
			val d = dialog as BottomSheetDialog
			val internalBottomSheet =
				d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
			internalBottomSheet?.let { bs ->
				val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bs)
				behavior.state =
					com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
				behavior.skipCollapsed = true

				// Adjust width for landscape/TV screens
				val displayMetrics = activity.resources.displayMetrics
				val screenWidth = displayMetrics.widthPixels
				val maxWidth = (600 * displayMetrics.density).toInt()
				if (screenWidth > maxWidth) {
					val params = bs.layoutParams
					params.width = maxWidth
					bs.layoutParams = params
				}
			}
		}

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
			startExport()
		}

		view.findViewById<MaterialButton>(R.id.btnMenuImport).setOnClickListener {
			bottomSheet.dismiss()
			startImport()
		}

		bottomSheet.show()
	}

	private fun requestTileUpdate() {
		TileServiceCompat.requestListeningState(
			activity,
			ComponentName(activity, DnsToggleService::class.java)
		)
	}
}
