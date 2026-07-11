package com.ericlowry.dnstoggle

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = DnsToggleApplication::class, sdk = [34])
class DnsSettingsRepositoryTest {

	@Before
	fun setup() {
		val context = ApplicationProvider.getApplicationContext<DnsToggleApplication>()
		DnsSettingsRepository.initialize(context)
	}

	@Test
	fun addHostname_doesNotDuplicate() = runTest {
		val hostname = "dns.google"

		// Test duplicates handling
		DnsSettingsRepository.addHostname(hostname)
		DnsSettingsRepository.addHostname(hostname)

		val hostnames = DnsSettingsRepository.dnsHostnames.value
		assertTrue(hostnames != null)
		assertEquals(1, hostnames?.count { it == hostname })
	}

	@Test
	fun addSsidToBlacklist_doesNotDuplicate() = runTest {
		val ssid = "MyHomeWiFi"

		DnsSettingsRepository.addToBlacklist(ssid)
		DnsSettingsRepository.addToBlacklist(ssid)

		val blacklist = DnsSettingsRepository.blacklist.value
		assertTrue(blacklist != null)
		assertEquals(1, blacklist?.count { it == ssid })
	}

	@Test
	fun removeHostname_keepsAtLeastOne() = runTest {
		val hostname = "dns.google"
		DnsSettingsRepository.addHostname(hostname)

		// The last entry shouldn't be removed
		DnsSettingsRepository.removeHostname(hostname)

		val hostnames = DnsSettingsRepository.dnsHostnames.value
		assertTrue(hostnames?.contains(hostname) == true)
	}
}
