package com.ericlowry.dnstoggle

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log

import java.security.GeneralSecurityException
import java.security.KeyStore

import javax.crypto.AEADBadTagException
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

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun getKey(): SecretKey {
        val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return existingKey?.secretKey ?: createKey()
    }

    private fun createKey(): SecretKey {
        return KeyGenerator.getInstance(ALGORITHM, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(BLOCK_MODE)
                    .setEncryptionPaddings(PADDING)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    fun encrypt(data: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        
        // Combine IV and encrypted data: [IV length (1 byte)][IV][Encrypted Data]
        val combined = ByteArray(1 + iv.size + encryptedData.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encryptedData, 0, combined, 1 + iv.size, encryptedData.size)
        
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    sealed class DecryptResult {
        data class Success(val data: String) : DecryptResult()
        object KeyInvalidated : DecryptResult()
        object Failed : DecryptResult()
    }

    fun decrypt(encryptedBase64: String): DecryptResult {
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.isEmpty()) return DecryptResult.Failed
            
            val ivSize = combined[0].toInt()
            if (ivSize <= 0 || ivSize > combined.size - 1) return DecryptResult.Failed
            
            val iv = ByteArray(ivSize)
            System.arraycopy(combined, 1, iv, 0, ivSize)
            
            val encryptedDataSize = combined.size - 1 - ivSize
            if (encryptedDataSize <= 0) return DecryptResult.Failed
            
            val encryptedData = ByteArray(encryptedDataSize)
            System.arraycopy(combined, 1 + ivSize, encryptedData, 0, encryptedDataSize)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getKey(), spec)
            
            DecryptResult.Success(String(cipher.doFinal(encryptedData), Charsets.UTF_8))
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.e(TAG, "Key was permanently invalidated. Recreating...", e)
            deleteKey()
            DecryptResult.KeyInvalidated
        } catch (e: AEADBadTagException) {
            Log.e(TAG, "Decryption failed: AEAD tag mismatch. The data might be corrupted or the key is wrong.", e)
            DecryptResult.Failed
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Cryptographic error during decryption", e)
            DecryptResult.Failed
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during decryption", e)
            DecryptResult.Failed
        }
    }

    private fun deleteKey() {
        try {
            keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete key from Keystore", e)
        }
    }
}
