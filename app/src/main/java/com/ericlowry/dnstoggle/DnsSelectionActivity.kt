package com.ericlowry.dnstoggle

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DnsSelectionActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		lifecycleScope.launch {
			val hostnames = DnsSettingsRepository.dnsHostnames.first { it != null } ?: emptySet()
			if (hostnames.size <= 1) {
				startActivity(Intent(this@DnsSelectionActivity, MainActivity::class.java).apply {
					flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
				})
				finish()
				return@launch
			}

			setupPopup(hostnames)
		}
	}

	private fun setupPopup(hostnames: Set<String>) {
		val dialogView = LayoutInflater.from(this)
			.inflate(R.layout.dialog_dns_selection, findViewById(android.R.id.content), false)
		setContentView(dialogView)

		ViewCompat.setOnApplyWindowInsetsListener(dialogView) { view, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			view.setPadding(0, 0, 0, systemBars.bottom)
			insets
		}

		val tvPopupTitle = dialogView.findViewById<TextView>(R.id.tvPopupTitle)
		val prefs = (application as DnsToggleApplication).getPrefs()
		tvPopupTitle.text =
			prefs.getString(Constants.PREF_DYNAMIC_APP_NAME, getString(R.string.app_name))

		val listContainer = dialogView.findViewById<LinearLayout>(R.id.dnsListContainer)
		val btnSettings = dialogView.findViewById<MaterialButton>(R.id.btnSettings)

		val currentSpecifier =
			Settings.Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
		val currentMode =
			Settings.Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)

		fun selectOption(selectedIndex: Int, onSelected: () -> Unit) {
			listContainer.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)

			updateSelectionVisuals(listContainer, selectedIndex)

			listContainer.postDelayed({
				onSelected()
				finish()
			}, 250)
		}

		val totalItems = hostnames.size + 1
		var currentPosition = 0

		hostnames.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { hostname ->
			val isActive =
				(hostname == currentSpecifier && currentMode == Constants.DNS_MODE_HOSTNAME)
			val index = currentPosition

			val itemView = createListItem(
				parent = listContainer,
				text = hostname,
				isActive = isActive,
				position = currentPosition,
				totalItems = totalItems
			) {
				selectOption(index) {
					DnsManager.togglePrivateDns(this@DnsSelectionActivity, true, hostname)
				}
			}
			listContainer.addView(itemView)
			currentPosition++
		}

		val isAutomaticActive = (currentMode != Constants.DNS_MODE_HOSTNAME)
		val autoIndex = currentPosition

		val autoItemView = createListItem(
			parent = listContainer,
			text = getString(R.string.automatic_off),
			isActive = isAutomaticActive,
			position = currentPosition,
			totalItems = totalItems
		) {
			selectOption(autoIndex) {
				DnsManager.togglePrivateDns(this@DnsSelectionActivity, false)
			}
		}
		listContainer.addView(autoItemView)

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
		isActive: Boolean,
		position: Int,
		totalItems: Int,
		onClick: () -> Unit
	): View {
		val itemView = LayoutInflater.from(this).inflate(R.layout.item_dns_selection, parent, false)
		val listItemLayout = itemView as ListItemLayout
		val cardView = itemView.findViewById<ListItemCardView>(R.id.listItemCard)
		val textView = itemView.findViewById<TextView>(R.id.tvHostname)
		val radioButton = itemView.findViewById<MaterialRadioButton>(R.id.radioDns)

		textView.text = text
		cardView.isChecked = isActive
		radioButton.isChecked = isActive

		listItemLayout.updateAppearance(position, totalItems)
		cardView.setOnClickListener { onClick() }

		return itemView
	}

	private var isFinishingAnimated = false

	override fun finish() {
		if (isFinishingAnimated) {
			super.finish()
			if (android.os.Build.VERSION.SDK_INT >= 34) {
				overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
			} else {
				@Suppress("DEPRECATION")
				overridePendingTransition(0, 0)
			}
			return
		}

		val popupView = findViewById<View>(R.id.dialogRootCard)

		// Skip the animation if the view is not ready.
		if (popupView == null || popupView.height == 0) {
			isFinishingAnimated = true
			finish()
			return
		}

		isFinishingAnimated = true

		// Disable touches while closing
		window.setFlags(
			android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
			android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
		)

		popupView.animate()
			.translationY(-(popupView.height.toFloat() + popupView.y)) // Ensure it clears the top of the screen
			.alpha(0f)
			.setDuration(250)
			.setInterpolator(android.view.animation.AccelerateInterpolator())
			.withEndAction {
				finish()
			}
			.start()
	}
}