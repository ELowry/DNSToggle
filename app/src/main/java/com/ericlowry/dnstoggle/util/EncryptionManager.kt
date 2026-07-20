package com.ericlowry.dnstoggle.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object EncryptionManager {

	private const val TAG = "EncryptionManager"
	private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
	private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
	private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
	private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"
	private const val KEY_ALIAS = "dns_toggle_key"
	private const val PREFIX = "enc:"

	private val keyStore: KeyStore? by lazy {
		try {
			KeyStore.getInstance("AndroidKeyStore").apply {
				load(null)
			}
		} catch (e: Exception) {
			Log.e(TAG, "AndroidKeyStore initialization failed", e)
			null
		}
	}

	private fun getKey(): SecretKey? {
		val ks = keyStore ?: return null
		return try {
			val existingKey = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
			existingKey?.secretKey ?: createKey()
		} catch (_: Exception) {
			createKey()
		}
	}

	private fun createKey(): SecretKey? {
		return try {
			KeyGenerator.getInstance(ALGORITHM, "AndroidKeyStore").apply {
				init(
					KeyGenParameterSpec.Builder(
						KEY_ALIAS,
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

	fun encrypt(data: String): String {
		return try {
			val key = getKey() ?: return data
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.ENCRYPT_MODE, key)
			val iv = cipher.iv
			val encryptedData = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

			val combined = ByteArray(1 + iv.size + encryptedData.size)
			combined[0] = iv.size.toByte()
			System.arraycopy(iv, 0, combined, 1, iv.size)
			System.arraycopy(encryptedData, 0, combined, 1 + iv.size, encryptedData.size)

			PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
		} catch (e: Exception) {
			Log.e(TAG, "Encryption failed, falling back to plaintext", e)
			data
		}
	}

	sealed class DecryptResult {
		data class Success(val data: String) : DecryptResult()
		object KeyInvalidated : DecryptResult()
		object Failed : DecryptResult()
	}

	fun decrypt(input: String): DecryptResult {
		if (input.isEmpty()) return DecryptResult.Failed

		return if (input.startsWith(PREFIX)) {
			decryptInternal(input.substring(PREFIX.length))
		} else {
			// Try legacy decryption (no prefix)
			val result = decryptInternal(input)
			if (result is DecryptResult.Success || result is DecryptResult.KeyInvalidated) {
				result
			} else {
				// If legacy decryption failed, and it doesn't have the prefix, assume it is plaintext
				DecryptResult.Success(input)
			}
		}
	}

	private fun decryptInternal(encryptedBase64: String): DecryptResult {
		return try {
			val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
			if (combined.isEmpty()) return DecryptResult.Failed

			val ivSize = combined[0].toInt()
			if (ivSize <= 0 || (ivSize > (combined.size - 1))) return DecryptResult.Failed

			val iv = ByteArray(ivSize)
			System.arraycopy(combined, 1, iv, 0, ivSize)

			val encryptedDataSize = combined.size - 1 - ivSize
			if (encryptedDataSize <= 0) return DecryptResult.Failed

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
				Log.w(TAG, "Decryption failed for input of length ${encryptedBase64.length}: ${e.message}")
			}
			DecryptResult.Failed
		}
	}

	private fun deleteKey() {
		try {
			keyStore?.deleteEntry(KEY_ALIAS)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to delete key from Keystore", e)
		}
	}
}
