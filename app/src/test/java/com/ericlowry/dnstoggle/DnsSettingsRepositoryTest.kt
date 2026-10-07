package com.ericlowry.dnstoggle

import androidx.test.core.app.ApplicationProvider
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.SecurityRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = DnsToggleApplication::class, sdk = [34])
class DnsSettingsRepositoryTest {

	private val testDispatcher = StandardTestDispatcher()

	@Before
	fun setup() {
		NetworkProfileRepository.ioDispatcher = testDispatcher
		HostnameRepository.ioDispatcher = testDispatcher
		VpnRepository.ioDispatcher = testDispatcher

		val context = ApplicationProvider.getApplicationContext<DnsToggleApplication>()
		SecurityRepository.initialize()
		VpnRepository.initialize(context)
		NetworkProfileRepository.initialize(context)
		HostnameRepository.initialize(context)
		testDispatcher.scheduler.advanceUntilIdle()
	}

	@After
	fun tearDown() {
		testDispatcher.scheduler.advanceUntilIdle()
		NetworkProfileRepository.ioDispatcher = Dispatchers.IO
		HostnameRepository.ioDispatcher = Dispatchers.IO
		VpnRepository.ioDispatcher = Dispatchers.IO
	}

	@Test
	fun addHostname_doesNotDuplicate() = runTest {
		val hostname = "dns.google"

		// Test duplicates handling
		HostnameRepository.addHostname(hostname)
		HostnameRepository.addHostname(hostname)

		val hostnames = HostnameRepository.dnsHostnames.value
		assertTrue(hostnames != null)
		assertEquals(1, hostnames?.count { it.hostname == hostname })
	}

	@Test
	fun upsertNetworkProfile_doesNotDuplicate() = runTest {
		val ssid = "MyHomeWiFi"

		NetworkProfileRepository.upsertNetworkProfile(ssid, isEnabled = false)
		NetworkProfileRepository.upsertNetworkProfile(ssid, isEnabled = false)

		val profiles = NetworkProfileRepository.networkProfiles.value
		assertTrue(profiles != null)
		assertEquals(1, profiles?.count { it.ssid == ssid })
	}

	@Test
	fun upsertNetworkProfile_autoDetected_isSetCorrectly() = runTest {
		val ssid = "AutoDetectedWiFi"

		NetworkProfileRepository.upsertNetworkProfile(
			ssid,
			isEnabled = false,
			isAutoDetected = true
		)

		val profile = NetworkProfileRepository.networkProfiles.value?.find { it.ssid == ssid }
		assertTrue(profile != null)
		assertTrue(profile?.isAutoDetected == true)
		assertTrue(profile?.isEnabled == false)
	}

	@Test
	fun upsertNetworkProfile_preservesHostname() = runTest {
		val ssid = "PreserveHostWiFi"
		val hostname = "dns.google"

		NetworkProfileRepository.upsertNetworkProfile(
			ssid,
			isEnabled = true,
			targetHostname = hostname
		)

		NetworkProfileRepository.upsertNetworkProfile(
			ssid,
			isEnabled = false,
			preserveExistingHostname = true
		)

		val profile = NetworkProfileRepository.networkProfiles.value?.find { it.ssid == ssid }
		assertEquals(hostname, profile?.targetHostname)
		assertEquals(false, profile?.isEnabled)
	}

	@Test
	fun upsertNetworkProfile_manual_notAutoDetected() = runTest {
		val ssid = "ManualWiFi"

		NetworkProfileRepository.upsertNetworkProfile(ssid, isEnabled = false)

		val profile = NetworkProfileRepository.networkProfiles.value?.find { it.ssid == ssid }
		assertTrue(profile != null)
		assertTrue(profile?.isAutoDetected == false)
	}

	@Test
	fun removeNetworkProfile_removesCorrectly() = runTest {
		val ssid = "AutoDetectedRemovable"

		NetworkProfileRepository.upsertNetworkProfile(
			ssid,
			isEnabled = false,
			isAutoDetected = true
		)
		NetworkProfileRepository.removeNetworkProfile(ssid)

		val profile = NetworkProfileRepository.networkProfiles.value?.find { it.ssid == ssid }
		assertTrue(profile == null)
	}

	@Test
	fun vpnDns_behavior() = runTest {
		val hostname = "dns.google"
		VpnRepository.updateVpnDns(Constants.DNS_MODE_HOSTNAME, hostname)
		assertEquals(Constants.DNS_MODE_HOSTNAME, VpnRepository.vpnDnsMode.value)
		assertEquals(hostname, VpnRepository.vpnDnsHostname.value)

		VpnRepository.updateVpnDns(Constants.DNS_MODE_OFF, null)
		assertEquals(Constants.DNS_MODE_OFF, VpnRepository.vpnDnsMode.value)
		assertEquals(null, VpnRepository.vpnDnsHostname.value)
	}

	@Test
	fun removeHostname_allowsEmptyList() = runTest {
		val hostname = "dns.google"
		HostnameRepository.addHostname(hostname)

		HostnameRepository.removeHostname(hostname)

		val hostnames = HostnameRepository.dnsHostnames.value
		assertTrue(hostnames?.isEmpty() == true)
	}
}
