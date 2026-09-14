package com.ericlowry.dnstoggle.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ericlowry.dnstoggle.R

/**
 * Singleton manager for handling system-level authentication (Biometrics and Device Credentials).
 */
object AuthManager {

	@Volatile
	private var temporaryAuthTimestamp: Long = 0L
	private const val AUTH_EXPIRATION_MS = 2000L

	fun grantTemporaryAuth() {
		temporaryAuthTimestamp = System.currentTimeMillis()
	}

	fun consumeTemporaryAuth(): Boolean {
		val currentTime = System.currentTimeMillis()
		val isValid =
			(temporaryAuthTimestamp > 0) && (currentTime - temporaryAuthTimestamp <= AUTH_EXPIRATION_MS)
		temporaryAuthTimestamp = 0L // Consume immediately
		return isValid
	}

	/**
	 * Prompts the user for system authentication using biometrics or device credentials.
	 *
	 * @param activity The [FragmentActivity] used to host the prompt.
	 * @param title The title displayed on the authentication prompt.
	 * @param subtitle Optional subtitle displayed on the authentication prompt.
	 * @param onSuccess Callback invoked upon successful authentication.
	 * @param onError Callback invoked when authentication fails or is unavailable.
	 */
	fun promptAuthentication(
		activity: FragmentActivity,
		title: String,
		subtitle: String? = null,
		onSuccess: () -> Unit,
		onError: (errorCode: Int, errString: CharSequence) -> Unit
	) {
		val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
				BiometricManager.Authenticators.DEVICE_CREDENTIAL

		val biometricManager = BiometricManager.from(activity)
		val canAuthenticate = biometricManager.canAuthenticate(authenticators)

		if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
			val errorString = getErrorString(activity, canAuthenticate)
			onError(canAuthenticate, errorString)
			return
		}

		val executor = ContextCompat.getMainExecutor(activity)
		val callback = object : BiometricPrompt.AuthenticationCallback() {
			override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
				super.onAuthenticationError(errorCode, errString)
				onError(errorCode, errString)
			}

			override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
				super.onAuthenticationSucceeded(result)
				onSuccess()
			}
		}

		val promptInfo = BiometricPrompt.PromptInfo.Builder().apply {
			setTitle(title)
			subtitle?.let {
				setSubtitle(it)
			}
			setAllowedAuthenticators(authenticators)
		}.build()

		BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
	}

	/**
	 * Maps [BiometricManager] error codes to localized user-friendly strings.
	 *
	 * @param context The [Context] used to retrieve localized strings.
	 * @param errorCode The error code returned by [BiometricManager.canAuthenticate].
	 * @return A descriptive error message.
	 */
	private fun getErrorString(context: Context, errorCode: Int): String {
		return when (errorCode) {
			BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
				context.getString(R.string.biometricErrorNoHardware)
			}

			BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
				context.getString(R.string.biometricErrorHwUnavailable)
			}

			BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
				context.getString(R.string.biometricErrorNoneEnrolled)
			}

			BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
				context.getString(R.string.biometricErrorSecurityUpdateRequired)
			}

			BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
				context.getString(R.string.biometricErrorUnsupported)
			}

			else -> {
				context.getString(R.string.biometricErrorDefault, errorCode)
			}
		}
	}
}
