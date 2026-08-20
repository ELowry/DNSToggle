package com.ericlowry.dnstoggle

import android.os.Looper
import android.provider.Settings
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.SecurityRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = DnsToggleApplication::class, sdk = [34])
class DnsViewModelTest {

	@get:Rule
	val instantTaskExecutorRule = InstantTaskExecutorRule()

	private val testDispatcher = UnconfinedTestDispatcher()

	private lateinit var app: DnsToggleApplication
	private lateinit var viewModel: DnsViewModel

	@Before
	fun setup() {
		Dispatchers.setMain(testDispatcher)
		app = ApplicationProvider.getApplicationContext()
		SecurityRepository.initialize(app)
		VpnRepository.initialize(app)
		NetworkProfileRepository.initialize(app)
		HostnameRepository.initialize(app)

		viewModel = DnsViewModel(app)
		viewModel.ioDispatcher = testDispatcher

		viewModel.dnsReachability.observeForever {}
		viewModel.dnsHostnames.observeForever {}
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun settingsChange_updatesLiveData() = runTest {
		val prefs = app.getPrefs()

		// Test AutoSaveState
		prefs.edit { putBoolean(Constants.PREF_AUTO_SAVE_STATE, true) }
		shadowOf(Looper.getMainLooper()).idle()
		assertEquals(true, viewModel.autoSaveStateEnabled.value)

		prefs.edit { putBoolean(Constants.PREF_AUTO_SAVE_STATE, false) }
		shadowOf(Looper.getMainLooper()).idle()
		assertEquals(false, viewModel.autoSaveStateEnabled.value)
	}

	@Test
	fun systemSettingsChange_updatesLiveData() = runTest {
		val resolver = app.contentResolver
		val mode = Constants.DNS_MODE_HOSTNAME
		val specifier = "dns.google"

		Settings.Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE, mode)
		Settings.Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER, specifier)

		viewModel.loadSettings()
		shadowOf(Looper.getMainLooper()).idle()

		assertEquals(mode, viewModel.privateDnsMode.value)
		assertEquals(specifier, viewModel.privateDnsSpecifier.value)
	}

	@Test
	fun refreshDisplayList_handlesUnsavedHostname() = runTest {
		val resolver = app.contentResolver
		val specifier = "unsaved.dns.com"

		Settings.Global.putString(
			resolver,
			Constants.SETTINGS_PRIVATE_DNS_MODE,
			Constants.DNS_MODE_HOSTNAME
		)
		Settings.Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER, specifier)

		viewModel.loadSettings()
		shadowOf(Looper.getMainLooper()).idle()

		val hostnames = viewModel.dnsHostnames.value
		assertNotNull("Hostnames list should not be null", hostnames)
		assertTrue(
			"Hostnames list should contain the unsaved specifier",
			hostnames!!.any { it.hostname == specifier && it.isUnsaved }
		)
	}

	@Test
	fun disableDnsTest_skipsReachability() = runTest {
		val prefs = app.getPrefs()
		prefs.edit { putBoolean(Constants.PREF_DISABLE_DNS_TEST, true) }
		shadowOf(Looper.getMainLooper()).idle()

		val hostname = "dns.google"
		viewModel.addHostname(hostname)
		shadowOf(Looper.getMainLooper()).idle()

		val reachability = viewModel.dnsReachability.value
		assertEquals(DnsViewModel.ReachabilityState.IDLE, reachability?.get(hostname))
	}

	@Test
	fun setEnableStrictOffOption_updatesPrefsAndSanitizes() = runTest {
		val prefs = app.getPrefs()

		viewModel.setEnableStrictOffOption(enabled = true)
		assertTrue(prefs.getBoolean(Constants.PREF_ENABLE_STRICT_OFF_OPTION, false))

		viewModel.setEnableStrictOffOption(enabled = false)
		assertFalse(prefs.getBoolean(Constants.PREF_ENABLE_STRICT_OFF_OPTION, true))
		assertEquals(
			Constants.DNS_MODE_OPPORTUNISTIC,
			prefs.getString(Constants.PREF_DEFAULT_OFF_MODE, null)
		)
	}
}
