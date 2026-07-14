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
	fun encryptDecrypt_worksCorrectly() {
		val original = "secret_dns_hostname"
		val encrypted = EncryptionManager.encrypt(original)
		val result = EncryptionManager.decrypt(encrypted)

		assertTrue(result is EncryptionManager.DecryptResult.Success)
		assertEquals(original, (result as EncryptionManager.DecryptResult.Success).data)
	}

	@Test
	fun decrypt_handlesEmptyString() {
		val result = EncryptionManager.decrypt("")
		assertEquals(EncryptionManager.DecryptResult.Failed, result)
	}

	@Test
	fun decrypt_handlesCorruptedData() {
		// Invalid Base64
		val result1 = EncryptionManager.decrypt("not-base64-!")
		assertEquals(EncryptionManager.DecryptResult.Failed, result1)

		// Valid Base64 but invalid format
		val result2 = EncryptionManager.decrypt("SGVsbG8gd29ybGQ=")
		assertEquals(EncryptionManager.DecryptResult.Failed, result2)
	}
}
