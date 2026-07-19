package com.ericlowry.dnstoggle

import com.ericlowry.dnstoggle.service.ConnectivityWatchdog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityWatchdogTest {

	@Test
	fun networkUnreachable_returnsFalse_regardlessOfDnsHost() = runTest {
		assertFalse(
			ConnectivityWatchdog.isDnsSpecificFailure(
				"dns.example.com",
				isNetworkReachable = { false },
				isDnsHostReachable = { false }
			)
		)
		assertFalse(
			ConnectivityWatchdog.isDnsSpecificFailure(
				"dns.example.com",
				isNetworkReachable = { false },
				isDnsHostReachable = { true }
			)
		)
	}

	@Test
	fun networkReachable_dnsHostReachable_returnsFalse() = runTest {
		assertFalse(
			ConnectivityWatchdog.isDnsSpecificFailure(
				"dns.example.com",
				isNetworkReachable = { true },
				isDnsHostReachable = { true }
			)
		)
	}

	@Test
	fun networkReachable_dnsHostUnreachable_returnsTrue() = runTest {
		assertTrue(
			ConnectivityWatchdog.isDnsSpecificFailure(
				"dns.example.com",
				isNetworkReachable = { true },
				isDnsHostReachable = { false }
			)
		)
	}

	@Test
	fun isRecovered_requiresBothNetworkAndDnsHostReachable() = runTest {
		assertTrue(
			ConnectivityWatchdog.isRecovered(
				"dns.example.com",
				isNetworkReachable = { true },
				isDnsHostReachable = { true }
			)
		)
		assertFalse(
			ConnectivityWatchdog.isRecovered(
				"dns.example.com",
				isNetworkReachable = { false },
				isDnsHostReachable = { true }
			)
		)
		assertFalse(
			ConnectivityWatchdog.isRecovered(
				"dns.example.com",
				isNetworkReachable = { true },
				isDnsHostReachable = { false }
			)
		)
	}
}
