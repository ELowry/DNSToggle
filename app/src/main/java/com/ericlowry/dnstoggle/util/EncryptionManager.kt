package com.ericlowry.dnstoggle.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.util.EncryptionManager.encrypt
import java.security.KeyStore
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

	private var dummyKey: SecretKey? = null

	/**
	 * Encrypts a string using an AES key stored in the Android Keystore.
	 *
	 * Blob format: [1 byte: IV size] + [n bytes: IV] + [m bytes: Payload]
	 *
	 * @param data The plaintext string to encrypt.
	 * @return A Base64 encoded string prefixed with [Constants.ENCRYPTION_PREFIX].
	 */
	fun encrypt(data: String): String {
		return try {
			val key = getKey() ?: throw Exception("KeyStore and dummy key unavailable")
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.ENCRYPT_MODE, key)
			val iv = cipher.iv
			val encryptedData = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

			val combined = ByteArray(1 + iv.size + encryptedData.size)
			combined[0] = iv.size.toByte()
			System.arraycopy(iv, 0, combined, 1, iv.size)
			System.arraycopy(encryptedData, 0, combined, 1 + iv.size, encryptedData.size)

			Constants.ENCRYPTION_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
		} catch (e: Exception) {
			Log.e(TAG, "Encryption failed", e)
			data
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
			if (dummyKey == null) {
				try {
					// Fallback to a plain AES key for environments without AndroidKeyStore (e.g. unit tests)
					dummyKey = SecretKeySpec(ByteArray(32), "AES")
				} catch (_: Exception) {
					Log.e(TAG, "Failed to create dummy key")
				}
			}
			dummyKey
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
		return try {
			val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
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

			val key = getKey() ?: return DecryptResult.Failed
			val cipher = Cipher.getInstance(TRANSFORMATION)
			val spec = GCMParameterSpec(128, iv)
			cipher.init(Cipher.DECRYPT_MODE, key, spec)

			DecryptResult.Success(String(cipher.doFinal(encryptedData), Charsets.UTF_8))
		} catch (_: KeyPermanentlyInvalidatedException) {
			Log.e(TAG, "Key was permanently invalidated")
			deleteKey()
			DecryptResult.KeyInvalidated
		} catch (e: Exception) {
			if (encryptedBase64.length > 20) {
				Log.w(
					TAG,
					"Decryption failed for input of length ${encryptedBase64.length}: ${e.message}"
				)
			}
			DecryptResult.Failed
		}
	}

	private fun deleteKey() {
		try {
			keyStore?.deleteEntry(Constants.ENCRYPTION_KEY_ALIAS)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to delete key from Keystore", e)
		}
	}
}
