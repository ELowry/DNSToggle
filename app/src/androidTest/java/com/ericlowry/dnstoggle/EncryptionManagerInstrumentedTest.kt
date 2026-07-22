package com.ericlowry.dnstoggle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ericlowry.dnstoggle.util.EncryptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptionManagerInstrumentedTest {

	@Test
	fun encrypt_usesKeyStoreAndAddsPrefix() {
		val original = "instrumented_secret_data"
		val encrypted = EncryptionManager.encrypt(original)

		assertTrue("Encrypted data must start with 'enc:' prefix", encrypted.startsWith("enc:"))
	}

	@Test
	fun encryptDecrypt_roundTripsSuccessfully() {
		val original = "instrumented_secret_data"
		val encrypted = EncryptionManager.encrypt(original)

		val result = EncryptionManager.decrypt(encrypted)

		assertTrue(
			"Decryption should return Success",
			result is EncryptionManager.DecryptResult.Success
		)
		assertEquals(original, (result as EncryptionManager.DecryptResult.Success).data)
	}
}
