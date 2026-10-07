package com.ericlowry.dnstoggle

import com.ericlowry.dnstoggle.service.ConnectivityWatchdog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityWatchdogTest {

	@Test
	fun dnsHostUnreachable_returnsTrue() = runTest {
		assertTrue(
			ConnectivityWatchdog.isDnsSpecificFailure(
				"dns.example.com",
				isDnsHostReachable = { false }
			)
		)
	}

	@Test
	fun dnsHostReachable_returnsFalse() = runTest {
		assertFalse(
			ConnectivityWatchdog.isDnsSpecificFailure(
				"dns.example.com",
				isDnsHostReachable = { true }
			)
		)
	}

	@Test
	fun isRecovered_requiresBothNetworkAndDnsHostReachable() = runTest {
		assertTrue(
			ConnectivityWatchdog.isRecovered(
				"dns.example.com",
				probeTargetsStr = "91.198.174.192,103.102.166.224,173.239.79.196",
				isNetworkReachable = { true },
				isDnsHostReachable = { true }
			)
		)
		assertFalse(
			ConnectivityWatchdog.isRecovered(
				"dns.example.com",
				probeTargetsStr = "91.198.174.192,103.102.166.224,173.239.79.196",
				isNetworkReachable = { false },
				isDnsHostReachable = { true }
			)
		)
		assertFalse(
			ConnectivityWatchdog.isRecovered(
				"dns.example.com",
				probeTargetsStr = "91.198.174.192,103.102.166.224,173.239.79.196",
				isNetworkReachable = { true },
				isDnsHostReachable = { false }
			)
		)
	}
}
