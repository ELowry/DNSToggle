package com.ericlowry.dnstoggle

import com.ericlowry.dnstoggle.util.BackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupManagerTest {

	@Test
	fun `test encryption and decryption with correct password`() {
		val originalData = "{\"key\": \"value\", \"list\": [1, 2, 3]}"
		val password = "strongpassword".toCharArray()

		val encrypted = BackupManager.encryptBackup(originalData, password.copyOf())
		val decrypted = BackupManager.decryptBackup(encrypted, password.copyOf())

		assertEquals(originalData, decrypted)
	}

	@Test
	fun `test decryption with incorrect password fails`() {
		val originalData = "secret data"
		val password = "correct_password".toCharArray()
		val wrongPassword = "wrong_password".toCharArray()

		val encrypted = BackupManager.encryptBackup(originalData, password)
		val decrypted = BackupManager.decryptBackup(encrypted, wrongPassword)

		assertNull(decrypted)
	}

	@Test
	fun `test encrypted data is different each time for same input`() {
		val data = "same data"
		val password = "password".toCharArray()

		val encrypted1 = BackupManager.encryptBackup(data, password.copyOf())
		val encrypted2 = BackupManager.encryptBackup(data, password.copyOf())

		assertNotEquals(encrypted1, encrypted2)
	}
}
