package com.ericlowry.dnstoggle.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.ericlowry.dnstoggle.data.Constants
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manager for transparently encrypting and decrypting sensitive user data using the Android Keystore.
 * Primarily used for storing custom hostnames and network profiles in SharedPreferences.
 */
object EncryptionManager {

	private const val TAG = "EncryptionManager"
	private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
	private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
	private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
	private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"
	private const val FALLBACK_PREFS = "encryption_fallback_prefs"
	private const val PREF_FALLBACK_KEY = "fallback_aes_key"

	private val keyStore: KeyStore? by lazy {
		try {
			KeyStore.getInstance("AndroidKeyStore").apply {
				load(null)
			}
		} catch (_: Exception) {
			Log.w(TAG, "AndroidKeyStore not available, falling back to default provider")
			null
		}
	}

	private var appContext: Context? = null

	/**
	 * Initializes the manager with the application context.
	 *
	 * @param context The context used to retrieve application context for fallback key persistence.
	 */
	fun initialize(context: Context) {
		appContext = context.applicationContext
	}

	/**
	 * Encrypts a string using an AES key stored in the Android Keystore.
	 *
	 * Blob format: [1 byte: IV size] + [n bytes: IV] + [m bytes: Payload]
	 *
	 * @param data The plaintext string to encrypt.
	 * @return A Base64 encoded string prefixed with [Constants.ENCRYPTION_PREFIX].
	 */
	fun encrypt(data: String): String {
		val key = getKey() ?: getFallbackKey() ?: return data
		return try {
			encryptWithKey(data, key)
		} catch (e: Exception) {
			Log.w(TAG, "Encryption with primary key failed, attempting fallback key", e)
			val fallbackKey = getFallbackKey()
			if ((fallbackKey != null) && (fallbackKey != key)) {
				try {
					encryptWithKey(data, fallbackKey)
				} catch (fallbackEx: Exception) {
					Log.e(TAG, "Encryption with fallback key also failed", fallbackEx)
					data
				}
			} else {
				data
			}
		}
	}

	sealed class DecryptResult {
		data class Success(val data: String) : DecryptResult()
		object KeyInvalidated : DecryptResult()
		object Failed : DecryptResult()
	}

	/**
	 * Decrypts a string that was previously encrypted by [encrypt].
	 *
	 * @param input The Base64 encoded string to decrypt.
	 * @return A [DecryptResult] containing the plaintext or an error state.
	 */
	fun decrypt(input: String): DecryptResult {
		if (input.isEmpty()) {
			return DecryptResult.Failed
		}

		return if (input.startsWith(Constants.ENCRYPTION_PREFIX)) {
			decryptInternal(input.substring(Constants.ENCRYPTION_PREFIX.length))
		} else {
			DecryptResult.Failed
		}
	}

	private fun encryptWithKey(data: String, key: SecretKey): String {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, key)
		val iv = cipher.iv
		val encryptedData = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

		val combined = ByteArray(1 + iv.size + encryptedData.size)
		combined[0] = iv.size.toByte()
		System.arraycopy(iv, 0, combined, 1, iv.size)
		System.arraycopy(encryptedData, 0, combined, 1 + iv.size, encryptedData.size)

		return Constants.ENCRYPTION_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
	}

	private fun decryptWithKey(
		encryptedData: ByteArray,
		iv: ByteArray,
		key: SecretKey,
	): DecryptResult {
		return try {
			val cipher = Cipher.getInstance(TRANSFORMATION)
			val spec = GCMParameterSpec(128, iv)
			cipher.init(Cipher.DECRYPT_MODE, key, spec)

			DecryptResult.Success(String(cipher.doFinal(encryptedData), Charsets.UTF_8))
		} catch (_: KeyPermanentlyInvalidatedException) {
			Log.e(TAG, "Key was permanently invalidated")
			deleteKey()
			DecryptResult.KeyInvalidated
		} catch (e: Exception) {
			if (encryptedData.size > 20) {
				Log.w(
					TAG,
					"Decryption failed for payload of length ${encryptedData.size}: ${e.message}"
				)
			}
			DecryptResult.Failed
		}
	}

	@Synchronized
	private fun getFallbackKey(): SecretKey? {
		val context = appContext ?: return null
		val prefs = context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)

		val existingKeyBase64 = prefs.getString(PREF_FALLBACK_KEY, null)
		if (existingKeyBase64 != null) {
			try {
				val keyBytes = Base64.decode(existingKeyBase64, Base64.NO_WRAP)
				return SecretKeySpec(keyBytes, "AES")
			} catch (e: Exception) {
				Log.e(TAG, "Failed to decode existing fallback key, generating a new one", e)
				// Fall through to generate a new key
			}
		}

		return try {
			val keyGenerator = KeyGenerator.getInstance("AES")
			keyGenerator.init(256, SecureRandom())
			val newKey = keyGenerator.generateKey()

			val newKeyBase64 = Base64.encodeToString(newKey.encoded, Base64.NO_WRAP)
			prefs.edit {
				putString(PREF_FALLBACK_KEY, newKeyBase64)
			}

			newKey
		} catch (e: Exception) {
			Log.e(TAG, "Failed to generate fallback key", e)
			null
		}
	}

	private fun getKey(): SecretKey? {
		val ks = keyStore
		return if (ks != null) {
			try {
				val existingKey =
					ks.getEntry(Constants.ENCRYPTION_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
				existingKey?.secretKey ?: createKeyInAndroidKeyStore()
			} catch (_: Exception) {
				createKeyInAndroidKeyStore()
			}
		} else {
			getFallbackKey()
		}
	}

	private fun createKeyInAndroidKeyStore(): SecretKey? {
		return try {
			KeyGenerator.getInstance(ALGORITHM, "AndroidKeyStore").apply {
				init(
					KeyGenParameterSpec.Builder(
						Constants.ENCRYPTION_KEY_ALIAS,
						KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
					)
						.setBlockModes(BLOCK_MODE)
						.setEncryptionPaddings(PADDING)
						.setUserAuthenticationRequired(false)
						.setRandomizedEncryptionRequired(true)
						.build(),
				)
			}.generateKey()
		} catch (e: Exception) {
			Log.e(TAG, "Failed to create key in AndroidKeyStore", e)
			null
		}
	}

	private fun decryptInternal(encryptedBase64: String): DecryptResult {
		val combined = try {
			Base64.decode(encryptedBase64, Base64.NO_WRAP)
		} catch (_: Exception) {
			return DecryptResult.Failed
		}
		if (combined.isEmpty()) {
			return DecryptResult.Failed
		}

		val ivSize = combined[0].toInt()
		if (ivSize <= 0 || (ivSize > (combined.size - 1))) {
			return DecryptResult.Failed
		}

		val iv = ByteArray(ivSize)
		System.arraycopy(combined, 1, iv, 0, ivSize)

		val encryptedDataSize = combined.size - 1 - ivSize
		if (encryptedDataSize <= 0) {
			return DecryptResult.Failed
		}

		val encryptedData = ByteArray(encryptedDataSize)
		System.arraycopy(combined, 1 + ivSize, encryptedData, 0, encryptedDataSize)

		val primaryKey = getKey()
		if (primaryKey != null) {
			val primaryResult = decryptWithKey(encryptedData, iv, primaryKey)
			if (primaryResult is DecryptResult.Success || primaryResult is DecryptResult.KeyInvalidated) {
				return primaryResult
			}
		}

		val fallbackKey = getFallbackKey()
		if (fallbackKey != null && fallbackKey != primaryKey) {
			return decryptWithKey(encryptedData, iv, fallbackKey)
		}

		return DecryptResult.Failed
	}

	private fun deleteKey() {
		try {
			keyStore?.deleteEntry(Constants.ENCRYPTION_KEY_ALIAS)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to delete key from Keystore", e)
		}
	}
}
