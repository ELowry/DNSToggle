package com.ericlowry.dnstoggle

import android.provider.Settings
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.SecurityRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import com.ericlowry.dnstoggle.service.ConnectivityWatchdogManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = DnsToggleApplication::class, sdk = [34])
class ConnectivityWatchdogManagerTest {

	private lateinit var app: DnsToggleApplication
	private lateinit var watchdogManager: ConnectivityWatchdogManager
	private val testScope = TestScope()

	@Before
	fun setup() {
		app = ApplicationProvider.getApplicationContext()
		SecurityRepository.initialize(app)
		VpnRepository.initialize(app)
		NetworkProfileRepository.initialize(app)
		HostnameRepository.initialize(app)

		watchdogManager = ConnectivityWatchdogManager(
			context = app,
			serviceScope = testScope,
			isDnsSpecificFailureFunc = { _, _ -> true },
			isRecoveredFunc = { _, _ -> true }
		)
	}

	@Test
	fun evaluateConnectivityWatchdog_triggersProfileCreationAfterDebounce() = testScope.runTest {
		val ssid = "WatchdogSSID"
		val debounceSeconds = 10
		app.getPrefs().edit {
			putBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, true)
			putInt(Constants.PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS, debounceSeconds)
		}
		Settings.Global.putString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_MODE,
			Constants.DNS_MODE_HOSTNAME
		)
		Settings.Global.putString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_SPECIFIER,
			"dns.google"
		)
		app.detectedSsid = ssid

		watchdogManager.evaluateConnectivityWatchdog(
			ssid,
			null,
			emptyMap(),
			Constants.DNS_MODE_HOSTNAME
		)

		// Advance time but not enough
		advanceTimeBy(5.seconds)
		assertNull(NetworkProfileRepository.networkProfiles.value?.find { it.ssid == ssid })

		// Advance past debounce
		advanceTimeBy(6.seconds)

		val profile = NetworkProfileRepository.networkProfiles.value?.find { it.ssid == ssid }
		assertNotNull("Profile should be created after debounce", profile)
		assertEquals(false, profile?.isEnabled)
		assertEquals(true, profile?.isAutoDetected)
	}

	@Test
	fun cancelAll_stopsActiveJobs() = testScope.runTest {
		val ssid = "CancelSSID"
		app.getPrefs().edit {
			putBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, true)
		}
		Settings.Global.putString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_MODE,
			Constants.DNS_MODE_HOSTNAME
		)
		Settings.Global.putString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_SPECIFIER,
			"dns.google"
		)
		app.detectedSsid = ssid

		watchdogManager.evaluateConnectivityWatchdog(
			ssid,
			null,
			emptyMap(),
			Constants.DNS_MODE_HOSTNAME
		)
		watchdogManager.cancelAll()

		advanceTimeBy(300.seconds) // Way past default debounce
		assertNull(NetworkProfileRepository.networkProfiles.value?.find { it.ssid == ssid })
	}
}
