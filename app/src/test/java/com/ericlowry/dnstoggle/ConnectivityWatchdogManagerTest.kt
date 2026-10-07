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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
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

	private val testDispatcher = StandardTestDispatcher()

	@Before
	fun setup() {
		NetworkProfileRepository.ioDispatcher = testDispatcher
		HostnameRepository.ioDispatcher = testDispatcher
		VpnRepository.ioDispatcher = testDispatcher

		app = ApplicationProvider.getApplicationContext()
		SecurityRepository.initialize()
		VpnRepository.initialize(app)
		NetworkProfileRepository.initialize(app)
		HostnameRepository.initialize(app)
		testDispatcher.scheduler.advanceUntilIdle()

		watchdogManager = ConnectivityWatchdogManager(
			context = app,
			serviceScope = testScope,
			isDnsSpecificFailureFunc = { _ -> true },
			isRecoveredFunc = { _, _ -> true }
		)
	}

	@After
	fun tearDown() {
		testDispatcher.scheduler.advanceUntilIdle()
		NetworkProfileRepository.ioDispatcher = Dispatchers.IO
		HostnameRepository.ioDispatcher = Dispatchers.IO
		VpnRepository.ioDispatcher = Dispatchers.IO
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
			Constants.DNS_MODE_HOSTNAME,
			false
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
			Constants.DNS_MODE_HOSTNAME,
			false
		)
		watchdogManager.cancelAll()

		advanceTimeBy(300.seconds) // Way past default debounce
		assertNull(NetworkProfileRepository.networkProfiles.value?.find { it.ssid == ssid })
	}

	@Test
	fun evaluateConnectivityWatchdog_captivePortalTriggersInstantlyWithoutDebounce() =
		testScope.runTest {
			val ssid = "CaptiveSSID"
			app.getPrefs().edit {
				putBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, true)
				putInt(Constants.PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS, 10)
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

			// First call with isCaptivePortal = false starts sleeping job
			watchdogManager.evaluateConnectivityWatchdog(
				ssid,
				null,
				emptyMap(),
				Constants.DNS_MODE_HOSTNAME,
				isCaptivePortal = false
			)

			// Second call with isCaptivePortal = true bypasses active job guard and skips debounce delay
			watchdogManager.evaluateConnectivityWatchdog(
				ssid,
				null,
				emptyMap(),
				Constants.DNS_MODE_HOSTNAME,
				isCaptivePortal = true
			)

			testScheduler.advanceUntilIdle()
			testDispatcher.scheduler.advanceUntilIdle()

			val profile = NetworkProfileRepository.networkProfiles.value?.find { it.ssid == ssid }
			assertNotNull("Profile should be created immediately for captive portal", profile)
			assertEquals(false, profile?.isEnabled)
			assertEquals(true, profile?.isAutoDetected)
		}
}
