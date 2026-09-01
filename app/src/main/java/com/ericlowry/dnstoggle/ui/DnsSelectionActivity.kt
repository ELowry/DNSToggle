package com.ericlowry.dnstoggle.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsHostname
import com.ericlowry.dnstoggle.data.DnsManager
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.service.UsbDebuggingTileService
import com.ericlowry.dnstoggle.util.EncryptionManager
import com.ericlowry.dnstoggle.util.NetworkUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DnsSelectionActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
		} else {
			@Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
		}

		if (component?.className == UsbDebuggingTileService::class.java.name) {
			startActivity(Intent(this, DeveloperOptionsActivity::class.java))
			finish()
			return
		}

		lifecycleScope.launch {
			val prefs = (application as DnsToggleApplication).getPrefs()
			val enableStrictOff = prefs.getBoolean(Constants.PREF_ENABLE_STRICT_OFF_OPTION, false)

			val hostnames = HostnameRepository.dnsHostnames.first { it != null } ?: emptyList()

			if (hostnames.size <= 1 && !enableStrictOff) {
				startActivity(Intent(this@DnsSelectionActivity, MainActivity::class.java).apply {
					flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
				})
				finish()
				return@launch
			}

			setupPopup(hostnames)
		}
	}

	override fun finish() {
		if (isFinishingAnimated) {
			super.finish()
			if (Build.VERSION.SDK_INT >= 34) {
				overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
			} else {
				@Suppress("DEPRECATION")
				overridePendingTransition(0, 0)
			}
			return
		}

		val popupView = findViewById<View>(R.id.dialogRootCard)

		if (popupView == null || popupView.height == 0) {
			isFinishingAnimated = true
			finish()
			return
		}

		isFinishingAnimated = true

		// Disable touches while closing
		window.setFlags(
			WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
			WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
		)

		popupView.animate()
			.translationY(-(popupView.height.toFloat() + popupView.y)) // Ensure it clears the top of the screen
			.alpha(0f)
			.setDuration(250)
			.setInterpolator(AccelerateInterpolator())
			.withEndAction {
				finish()
			}
			.start()
	}

	private var isFinishingAnimated = false

	private fun setupPopup(hostnames: List<DnsHostname>) {
		val dialogView = LayoutInflater.from(this)
			.inflate(R.layout.dialog_dns_selection, findViewById(android.R.id.content), false)
		setContentView(dialogView)

		ViewCompat.setOnApplyWindowInsetsListener(dialogView) { view, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			view.setPadding(0, 0, 0, systemBars.bottom)
			insets
		}

		val tvPopupTitle = dialogView.findViewById<TextView>(R.id.tvPopupTitle)
		val tvSsidContext = dialogView.findViewById<TextView>(R.id.tvSsidContext)
		val prefs = (application as DnsToggleApplication).getPrefs()

		val currentSsid = NetworkUtils.getCurrentWifiSsid(this)
		val autoSaveState = prefs.getBoolean(Constants.PREF_AUTO_SAVE_STATE, false)
		val autoSaveHost = prefs.getBoolean(Constants.PREF_AUTO_SAVE_HOST, false)
		val profiles = NetworkProfileRepository.networkProfiles.value ?: emptyList()
		val activeProfile = profiles.find { it.ssid == currentSsid }

		val isOverrideHostContext = currentSsid != null && autoSaveHost
		val isOverrideStateContext = currentSsid != null && autoSaveState
		val isShadowedContext =
			currentSsid != null && !autoSaveHost && !autoSaveState && activeProfile != null

		val currentProfile = if (isOverrideHostContext || isOverrideStateContext) {
			activeProfile
		} else {
			null
		}

		tvPopupTitle.text =
			prefs.getString(Constants.PREF_DYNAMIC_APP_NAME, getString(R.string.app_name))

		when {
			isOverrideHostContext -> {
				tvSsidContext.text = getString(R.string.qs_hostname_context, currentSsid)
				tvSsidContext.visibility = View.VISIBLE
			}

			isShadowedContext -> {
				tvSsidContext.text = getString(R.string.qs_shadowed_warning, currentSsid)
				tvSsidContext.visibility = View.VISIBLE
			}

			else -> tvSsidContext.visibility = View.GONE
		}

		val listContainer = dialogView.findViewById<LinearLayout>(R.id.dnsListContainer)
		val btnSettings = dialogView.findViewById<MaterialButton>(R.id.btnSettings)

		val systemMode =
			Settings.Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)

		val globalMode = prefs.getString(Constants.PREF_PREFERRED_DNS_MODE, systemMode)
		val enableStrictOff = prefs.getBoolean(Constants.PREF_ENABLE_STRICT_OFF_OPTION, false)
		val globalSpecifier = getGlobalFallbackHostname()

		fun selectOption(selectedIndex: Int, onSelected: () -> Unit) {
			listContainer.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

			updateSelectionVisuals(listContainer, selectedIndex)

			listContainer.postDelayed({
				onSelected()
				finish()
			}, 250)
		}

		val globalFallbackHostname = getGlobalFallbackHostname()
		val overrideHostContextCount = if (isOverrideHostContext) {
			2
		} else {
			1
		}
		val strictOffCount = if (enableStrictOff) {
			1
		} else {
			0
		}
		val totalItems = hostnames.size + overrideHostContextCount + strictOffCount
		var currentPosition = 0

		if (isOverrideHostContext) {
			val defaultLabel = if (globalFallbackHostname != null) {
				getString(R.string.default_dns_with_host_format, globalFallbackHostname)
			} else {
				getString(R.string.default_dns_label)
			}

			val isOverrideMatch =
				currentProfile?.isEnabled == true && currentProfile.targetHostname == null

			val isSubduedRadio = false
			val showOverrideBadge = false

			val index = currentPosition++

			val itemView = createListItem(
				parent = listContainer,
				text = defaultLabel,
				secondaryText = null,
				subtitleText = null,
				isCardChecked = isOverrideMatch,
				isRadioChecked = isOverrideMatch,
				isSubduedRadio = isSubduedRadio,
				showOverrideBadge = showOverrideBadge,
				position = index,
				totalItems = totalItems
			) {
				selectOption(index) {
					DnsManager.togglePrivateDns(
						context = this@DnsSelectionActivity,
						enabled = true,
						targetHostname = null,
						isFromTile = true
					)
				}
			}
			listContainer.addView(itemView)
		}

		hostnames.forEach { dnsEntry ->
			val hostname = dnsEntry.hostname

			val isGlobalMatch =
				(hostname == globalSpecifier && globalMode == Constants.DNS_MODE_HOSTNAME)
			val isOverrideMatch =
				(activeProfile?.isEnabled == true && activeProfile.targetHostname == hostname)

			val isCardChecked: Boolean
			val isRadioChecked: Boolean
			val isSubduedRadio: Boolean
			val showOverrideBadge: Boolean

			if (isShadowedContext) {
				isCardChecked = isGlobalMatch
				isRadioChecked = isGlobalMatch || isOverrideMatch
				isSubduedRadio = isGlobalMatch
				showOverrideBadge = isOverrideMatch
			} else if (isOverrideHostContext) {
				isCardChecked = isOverrideMatch
				isRadioChecked = isOverrideMatch
				isSubduedRadio = false
				showOverrideBadge = false
			} else {
				isCardChecked = isGlobalMatch
				isRadioChecked = isGlobalMatch
				isSubduedRadio = false
				showOverrideBadge = false
			}

			val index = currentPosition++

			val itemView = createListItem(
				parent = listContainer,
				text = dnsEntry.getDisplayName(),
				secondaryText = dnsEntry.label?.let { hostname },
				subtitleText = null,
				isCardChecked = isCardChecked,
				isRadioChecked = isRadioChecked,
				isSubduedRadio = isSubduedRadio,
				showOverrideBadge = showOverrideBadge,
				position = index,
				totalItems = totalItems
			) {
				selectOption(index) {
					DnsManager.togglePrivateDns(
						context = this@DnsSelectionActivity,
						enabled = true,
						targetHostname = hostname,
						isFromTile = true
					)
				}
			}
			listContainer.addView(itemView)
		}

		val isGlobalOffMatch = (globalMode == Constants.DNS_MODE_OPPORTUNISTIC)
		val isOverrideOffMatch =
			(activeProfile?.isEnabled == false && (activeProfile.targetMode == null || activeProfile.targetMode == Constants.DNS_MODE_OPPORTUNISTIC))

		val isOffCardChecked: Boolean
		val isOffRadioChecked: Boolean
		val isOffSubduedRadio: Boolean
		val showOffOverrideBadge: Boolean

		if (isShadowedContext) {
			isOffCardChecked = isGlobalOffMatch
			isOffRadioChecked = isGlobalOffMatch || isOverrideOffMatch
			isOffSubduedRadio = isGlobalOffMatch
			showOffOverrideBadge = isOverrideOffMatch
		} else if (isOverrideStateContext) {
			isOffCardChecked = isOverrideOffMatch
			isOffRadioChecked = isOverrideOffMatch
			isOffSubduedRadio = false
			showOffOverrideBadge = false
		} else {
			isOffCardChecked = isGlobalOffMatch
			isOffRadioChecked = isGlobalOffMatch
			isOffSubduedRadio = false
			showOffOverrideBadge = false
		}

		val offSubtitle = if (isOverrideStateContext) {
			getString(R.string.qs_ssid_context, currentSsid)
		} else {
			null
		}

		val autoIndex = currentPosition

		val autoItemView = createListItem(
			parent = listContainer,
			text = if (enableStrictOff) {
				getString(R.string.off_automatic_label)
			} else {
				getString(R.string.off_automatic_label)
			},
			secondaryText = null,
			subtitleText = offSubtitle,
			isCardChecked = isOffCardChecked,
			isRadioChecked = isOffRadioChecked,
			isSubduedRadio = isOffSubduedRadio,
			showOverrideBadge = showOffOverrideBadge,
			position = autoIndex,
			totalItems = totalItems
		) {
			selectOption(autoIndex) {
				DnsManager.togglePrivateDns(
					context = this@DnsSelectionActivity,
					enabled = false,
					targetMode = if (enableStrictOff) {
						Constants.DNS_MODE_OPPORTUNISTIC
					} else {
						null
					},
					isFromTile = true
				)
			}
		}
		listContainer.addView(autoItemView)

		if (enableStrictOff) {
			val isGlobalStrictMatch = (globalMode == Constants.DNS_MODE_OFF)
			val isOverrideStrictMatch =
				(activeProfile?.isEnabled == false && activeProfile.targetMode == Constants.DNS_MODE_OFF)

			val isStrictCardChecked: Boolean
			val isStrictRadioChecked: Boolean
			val isStrictSubduedRadio: Boolean
			val showStrictOverrideBadge: Boolean

			if (isShadowedContext) {
				isStrictCardChecked = isGlobalStrictMatch
				isStrictRadioChecked = isGlobalStrictMatch || isOverrideStrictMatch
				isStrictSubduedRadio = isGlobalStrictMatch
				showStrictOverrideBadge = isOverrideStrictMatch
			} else if (isOverrideStateContext) {
				isStrictCardChecked = isOverrideStrictMatch
				isStrictRadioChecked = isOverrideStrictMatch
				isStrictSubduedRadio = false
				showStrictOverrideBadge = false
			} else {
				isStrictCardChecked = isGlobalStrictMatch
				isStrictRadioChecked = isGlobalStrictMatch
				isStrictSubduedRadio = false
				showStrictOverrideBadge = false
			}

			val strictIndex = ++currentPosition

			val strictItemView = createListItem(
				parent = listContainer,
				text = getString(R.string.off_strict_label),
				secondaryText = null,
				subtitleText = offSubtitle,
				isCardChecked = isStrictCardChecked,
				isRadioChecked = isStrictRadioChecked,
				isSubduedRadio = isStrictSubduedRadio,
				showOverrideBadge = showStrictOverrideBadge,
				position = strictIndex,
				totalItems = totalItems
			) {
				selectOption(strictIndex) {
					DnsManager.togglePrivateDns(
						context = this@DnsSelectionActivity,
						enabled = false,
						targetMode = Constants.DNS_MODE_OFF,
						isFromTile = true
					)
				}
			}
			listContainer.addView(strictItemView)
		}

		btnSettings.setOnClickListener {
			startActivity(Intent(this, MainActivity::class.java))
			finish()
		}
	}

	private fun updateSelectionVisuals(container: ViewGroup, selectedIndex: Int) {
		for (i in 0 until container.childCount) {
			val itemView = container.getChildAt(i)
			val cardView = itemView.findViewById<ListItemCardView>(R.id.listItemCard)
			val radio = itemView.findViewById<MaterialRadioButton>(R.id.radioDns)

			val isSelected = (i == selectedIndex)
			cardView.isChecked = isSelected
			radio.isChecked = isSelected
		}
	}

	private fun createListItem(
		parent: ViewGroup,
		text: String,
		secondaryText: String?,
		subtitleText: String?,
		isCardChecked: Boolean,
		isRadioChecked: Boolean,
		isSubduedRadio: Boolean,
		showOverrideBadge: Boolean,
		position: Int,
		totalItems: Int,
		onClick: () -> Unit
	): View {
		val itemView = LayoutInflater.from(this).inflate(R.layout.item_dns_selection, parent, false)
		val listItemLayout = itemView as ListItemLayout
		val cardView = itemView.findViewById<ListItemCardView>(R.id.listItemCard)
		val textView = itemView.findViewById<TextView>(R.id.tvHostname)
		val secondaryTextView = itemView.findViewById<TextView>(R.id.tvSecondaryHostname)
		val overrideBadge = itemView.findViewById<TextView>(R.id.tvOverrideBadge)
		val radioButton = itemView.findViewById<MaterialRadioButton>(R.id.radioDns)

		textView.text = text
		if (subtitleText != null) {
			secondaryTextView.text = subtitleText
			secondaryTextView.visibility = View.VISIBLE
		} else if (secondaryText != null) {
			secondaryTextView.text = secondaryText
			secondaryTextView.visibility = View.VISIBLE
		} else {
			secondaryTextView.visibility = View.GONE
		}

		overrideBadge.visibility = if (showOverrideBadge) {
			View.VISIBLE
		} else {
			View.GONE
		}

		cardView.isChecked = isCardChecked
		radioButton.isChecked = isRadioChecked

		val radioColorAttr = when {
			showOverrideBadge -> {
				com.google.android.material.R.attr.colorTertiary
			}

			isSubduedRadio -> {
				com.google.android.material.R.attr.colorOutline
			}

			else -> {
				android.R.attr.colorPrimary
			}
		}
		val resolvedColor =
			com.google.android.material.color.MaterialColors.getColor(itemView, radioColorAttr)
		radioButton.buttonTintList = android.content.res.ColorStateList.valueOf(resolvedColor)

		listItemLayout.updateAppearance(position, totalItems)
		cardView.setOnClickListener { onClick() }

		return itemView
	}

	private fun getGlobalFallbackHostname(): String? {
		val app = application as DnsToggleApplication
		val encryptedPrefs = app.getEncryptedPrefs()
		val encryptedHostname = encryptedPrefs.getString(Constants.PREF_LAST_USED_HOSTNAME, null)
		val lastUsed = encryptedHostname?.let {
			when (val result = EncryptionManager.decrypt(it)) {
				is EncryptionManager.DecryptResult.Success -> {
					result.data
				}

				else -> {
					null
				}
			}
		}
		if (!lastUsed.isNullOrEmpty()) {
			return lastUsed
		}

		return Settings.Global.getString(
			contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_SPECIFIER
		)
	}
}
