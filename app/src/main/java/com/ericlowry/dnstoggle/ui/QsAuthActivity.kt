package com.ericlowry.dnstoggle.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsManager
import com.ericlowry.dnstoggle.util.AuthManager

/**
 * A transparent trampoline activity used to display authentication prompts for
 * background triggers like Quick Settings tiles and App Shortcuts.
 */
class QsAuthActivity : FragmentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Ensure the activity is transparent and has no UI of its own
		window.setBackgroundDrawableResource(android.R.color.transparent)

		val action = intent.action
		if (action == null) {
			finish()
			return
		}

		if (savedInstanceState == null) {
			val subtitleRes = if (action == Constants.ACTION_SELECT_DNS) {
				R.string.auth_prompt_unlock_subtitle
			} else {
				R.string.auth_prompt_action_subtitle
			}

			AuthManager.promptAuthentication(
				activity = this,
				title = getString(R.string.auth_prompt_title),
				subtitle = getString(subtitleRes),
				onSuccess = {
					handleAction(action)
					finish()
				},
				onError = { _, errString ->
					Toast.makeText(this, errString, Toast.LENGTH_SHORT)
						.show()
					finish()
				}
			)
		}
	}

	override fun onStop() {
		super.onStop()
		// If the user navigates away before completing the prompt, finish the activity
		if (!isFinishing) {
			finish()
		}
	}

	/**
	 * Executes the requested action after successful authentication.
	 *
	 * @param action The intent action to perform.
	 */
	private fun handleAction(action: String) {
		when (action) {
			Constants.ACTION_TOGGLE -> {
				val currentMode = Settings.Global.getString(
					contentResolver,
					Constants.SETTINGS_PRIVATE_DNS_MODE
				)
				val shouldEnable = currentMode != Constants.DNS_MODE_HOSTNAME

				DnsManager.togglePrivateDns(
					context = this,
					enabled = shouldEnable,
					forceFeedback = true,
					isFromTile = true
				)
			}

			Constants.ACTION_SELECT_DNS -> {
				val selectionIntent = Intent(this, DnsSelectionActivity::class.java).apply {
					flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
						this@QsAuthActivity.intent.getParcelableExtra(
							Intent.EXTRA_COMPONENT_NAME,
							ComponentName::class.java
						)?.let {
							putExtra(Intent.EXTRA_COMPONENT_NAME, it)
						}
					} else {
						@Suppress("DEPRECATION")
						this@QsAuthActivity.intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
							?.let {
								putExtra(Intent.EXTRA_COMPONENT_NAME, it)
							}
					}
				}
				AuthManager.grantTemporaryAuth()
				startActivity(selectionIntent)
			}
		}
	}
}
