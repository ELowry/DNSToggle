package com.ericlowry.dnstoggle.util

import android.util.Base64
import com.ericlowry.dnstoggle.data.Constants
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utility for encrypting and decrypting configuration backups.
 * Uses PBKDF2 for key derivation and AES-GCM for authenticated encryption.
 */
object BackupManager {

	/**
	 * Encrypts application configuration data with a user-provided password.
	 *
	 * Blob format: [1 byte: Salt size] + [n bytes: Salt] + [1 byte: IV size] + [m bytes: IV] + [x bytes: Ciphertext]
	 *
	 * @param jsonData The raw JSON configuration string.
	 * @param password The user-provided password for encryption.
	 * @return A Base64 encoded string containing the encrypted blob.
	 */
	fun encryptBackup(jsonData: String, password: CharArray): String {
		val salt = ByteArray(Constants.BACKUP_SALT_LENGTH)
		SecureRandom().nextBytes(salt)

		val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
		val spec = PBEKeySpec(
			password,
			salt,
			Constants.BACKUP_ITERATION_COUNT,
			Constants.BACKUP_KEY_LENGTH
		)
		val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, secretKey)
		val iv = cipher.iv
		val ciphertext = cipher.doFinal(jsonData.toByteArray(Charsets.UTF_8))

		val combined = ByteArray(1 + salt.size + 1 + iv.size + ciphertext.size)
		var offset = 0

		combined[offset++] = salt.size.toByte()
		System.arraycopy(salt, 0, combined, offset, salt.size)
		offset += salt.size

		combined[offset++] = iv.size.toByte()
		System.arraycopy(iv, 0, combined, offset, iv.size)
		offset += iv.size

		System.arraycopy(ciphertext, 0, combined, offset, ciphertext.size)
		password.fill('\u0000')

		return Base64.encodeToString(combined, Base64.NO_WRAP)
	}

	/**
	 * Decrypts an application configuration backup with a user-provided password.
	 *
	 * @param encryptedBase64 The Base64 encoded encrypted blob.
	 * @param password The user-provided password for decryption.
	 * @return The decrypted JSON string, or null if decryption fails (e.g. wrong password).
	 */
	fun decryptBackup(encryptedBase64: String, password: CharArray): String? {
		return try {
			val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
			var offset = 0

			val saltSize = combined[offset++].toInt() and 0xFF
			val salt = ByteArray(saltSize)
			System.arraycopy(combined, offset, salt, 0, saltSize)
			offset += saltSize

			val ivSize = combined[offset++].toInt() and 0xFF
			val iv = ByteArray(ivSize)
			System.arraycopy(combined, offset, iv, 0, ivSize)
			offset += ivSize

			val ciphertext = ByteArray(combined.size - offset)
			System.arraycopy(combined, offset, ciphertext, 0, ciphertext.size)

			val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
			val spec = PBEKeySpec(
				password,
				salt,
				Constants.BACKUP_ITERATION_COUNT,
				Constants.BACKUP_KEY_LENGTH
			)
			val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
			val plaintext = cipher.doFinal(ciphertext)

			password.fill('\u0000')
			String(plaintext, Charsets.UTF_8)
		} catch (_: Exception) {
			password.fill('\u0000')
			null
		}
	}
}
