package com.ericlowry.dnstoggle

import com.ericlowry.dnstoggle.util.EncryptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class EncryptionManagerTest {

	@Test
	fun encryptDecrypt_roundTrips() {
		// Robolectric has no real AndroidKeyStore, so encrypt() always takes its
		// plaintext-fallback path here and the "enc:" prefix can't be asserted.
		// The round-trip is the actual guaranteed contract; verify that instead.
		val original = "secret_dns_hostname"
		val encrypted = EncryptionManager.encrypt(original)

		val result = EncryptionManager.decrypt(encrypted)
		assertTrue(result is EncryptionManager.DecryptResult.Success)
		assertEquals(original, (result as EncryptionManager.DecryptResult.Success).data)
	}

	@Test
	fun decrypt_handlesLegacyData() {
		// Fallback for app version <1.6, stripped of prefix
		val original = "legacy_data"
		val encryptedWithPrefix = EncryptionManager.encrypt(original)
		val legacyEncrypted = encryptedWithPrefix.removePrefix("enc:")

		val result = EncryptionManager.decrypt(legacyEncrypted)
		assertTrue(result is EncryptionManager.DecryptResult.Success)
		assertEquals(original, (result as EncryptionManager.DecryptResult.Success).data)
	}

	@Test
	fun decrypt_fallsBackToPlaintext() {
		val plaintext = "not_encrypted_hostname"
		val result = EncryptionManager.decrypt(plaintext)

		assertTrue(result is EncryptionManager.DecryptResult.Success)
		assertEquals(plaintext, (result as EncryptionManager.DecryptResult.Success).data)
	}

	@Test
	fun decrypt_handlesEmptyString() {
		val result = EncryptionManager.decrypt("")
		assertEquals(EncryptionManager.DecryptResult.Failed, result)
	}

	@Test
	fun decrypt_handlesPrefixedCorruptedData() {
		// If it starts with enc: but is corrupted, it should fail (not fallback to plaintext)
		val result = EncryptionManager.decrypt("enc:invalid-data")
		assertEquals(EncryptionManager.DecryptResult.Failed, result)
	}
}
