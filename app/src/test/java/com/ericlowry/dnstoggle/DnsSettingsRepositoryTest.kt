package com.ericlowry.dnstoggle

import androidx.test.core.app.ApplicationProvider
import com.ericlowry.dnstoggle.data.DnsSettingsRepository
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
		assertEquals(1, hostnames?.count { it.hostname == hostname })
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
	fun addToBlacklist_autoDetected_appearsInBothSets() = runTest {
		val ssid = "AutoDetectedWiFi"

		DnsSettingsRepository.addToBlacklist(ssid, autoDetected = true)

		assertTrue(DnsSettingsRepository.blacklist.value?.contains(ssid) == true)
		assertTrue(DnsSettingsRepository.autoDetectedBlacklist.value?.contains(ssid) == true)
	}

	@Test
	fun addToBlacklist_manual_notInAutoDetectedSet() = runTest {
		val ssid = "ManualWiFi"

		DnsSettingsRepository.addToBlacklist(ssid)

		assertTrue(DnsSettingsRepository.blacklist.value?.contains(ssid) == true)
		assertTrue(DnsSettingsRepository.autoDetectedBlacklist.value?.contains(ssid) != true)
	}

	@Test
	fun removeFromBlacklist_autoDetected_removedFromBothSets() = runTest {
		val ssid = "AutoDetectedRemovable"

		DnsSettingsRepository.addToBlacklist(ssid, autoDetected = true)
		DnsSettingsRepository.removeFromBlacklist(ssid)

		assertTrue(DnsSettingsRepository.blacklist.value?.contains(ssid) != true)
		assertTrue(DnsSettingsRepository.autoDetectedBlacklist.value?.contains(ssid) != true)
	}

	@Test
	fun vpnDnsHostname_nullableBehavior() = runTest {
		val hostname = "dns.google"
		DnsSettingsRepository.updateVpnDnsHostname(hostname)
		assertEquals(hostname, DnsSettingsRepository.vpnDnsHostname.value)

		DnsSettingsRepository.updateVpnDnsHostname(null)
		assertEquals(null, DnsSettingsRepository.vpnDnsHostname.value)
	}

	@Test
	fun removeHostname_allowsEmptyList() = runTest {
		val hostname = "dns.google"
		DnsSettingsRepository.addHostname(hostname)

		DnsSettingsRepository.removeHostname(hostname)

		val hostnames = DnsSettingsRepository.dnsHostnames.value
		assertTrue(hostnames?.isEmpty() == true)
	}
}
