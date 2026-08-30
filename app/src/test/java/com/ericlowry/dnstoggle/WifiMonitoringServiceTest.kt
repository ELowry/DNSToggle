package com.ericlowry.dnstoggle

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.repository.DnsSettingsRepository
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.SecurityRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import com.ericlowry.dnstoggle.service.WifiMonitoringService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = DnsToggleApplication::class, sdk = [34])
class WifiMonitoringServiceTest {

	private lateinit var app: DnsToggleApplication
	private lateinit var connectivityManager: ConnectivityManager
	private lateinit var shadowConnectivityManager: ShadowConnectivityManager
	private val testDispatcher = StandardTestDispatcher()

	@Before
	fun setup() {
		Dispatchers.setMain(testDispatcher)
		app = ApplicationProvider.getApplicationContext()
		app.unregisterAllInternalObservers()

		SecurityRepository.initialize(app)
		VpnRepository.initialize(app)
		NetworkProfileRepository.initialize(app)
		HostnameRepository.initialize(app)
		DnsSettingsRepository.initialize(app)

		// Ensure clean state for overrides
		app.getPrefs().edit(commit = true) {
			remove(Constants.PREF_IS_IN_VPN_OVERRIDE)
			remove(Constants.PREF_ACTIVE_SSID_OVERRIDE)
			remove(Constants.PREF_VPN_OVERRIDE_ENABLED)
		}

		connectivityManager =
			app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
		shadowConnectivityManager = shadowOf(connectivityManager)
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun setupService(): WifiMonitoringService {
		val controller = Robolectric.buildService(WifiMonitoringService::class.java)
		val service = controller.get()
		service.mainDispatcher = testDispatcher
		service.ioDispatcher = testDispatcher
		controller.create().startCommand(0, 0)
		testDispatcher.scheduler.advanceUntilIdle()
		return service
	}

	@Test
	fun vpnConnected_withVpnOverrideEnabled_appliesVpnDns() = runTest(testDispatcher) {
		setupService()

		app.getPrefs().edit(commit = true) {
			putBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, true)
		}
		testDispatcher.scheduler.advanceUntilIdle()

		VpnRepository.updateVpnDns(Constants.DNS_MODE_HOSTNAME, "vpn.dns.com")
		testDispatcher.scheduler.advanceUntilIdle()

		val caps = ShadowNetworkCapabilities.newInstance()
		shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
		shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

		val network = ShadowNetwork.newInstance(1)
		val networkInfo = ShadowNetworkInfo.newInstance(
			NetworkInfo.DetailedState.CONNECTED,
			ConnectivityManager.TYPE_VPN,
			0,
			true,
			true
		)
		shadowConnectivityManager.addNetwork(network, networkInfo)
		shadowConnectivityManager.setNetworkCapabilities(network, caps)

		// Settle and evaluate
		testDispatcher.scheduler.advanceUntilIdle()
		// Wait long enough for both settle delay and any potential restoration debounce
		advanceTimeBy(2100.milliseconds)
		testDispatcher.scheduler.advanceUntilIdle()

		assertEquals(
			Constants.DNS_MODE_HOSTNAME,
			Settings.Global.getString(app.contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		)
		assertEquals(
			"vpn.dns.com",
			Settings.Global.getString(app.contentResolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
		)
	}

	@Test
	fun wifiConnected_withProfile_appliesProfileDns() = runTest(testDispatcher) {
		val ssid = "HomeWifi"
		val hostname = "home.dns.com"
		NetworkProfileRepository.upsertNetworkProfile(ssid, true, hostname)
		testDispatcher.scheduler.advanceUntilIdle()

		setupService()

		// Grant permission so the service can read SSID from transportInfo
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			shadowOf(app).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
		} else {
			shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
		}

		val wifiInfo = ShadowWifiInfo.newInstance()
		shadowOf(wifiInfo).setSSID(ssid)

		val caps = ShadowNetworkCapabilities.newInstance()
		shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
		shadowOf(caps).setTransportInfo(wifiInfo)
		shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
		shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

		val network = ShadowNetwork.newInstance(2)
		val networkInfo = ShadowNetworkInfo.newInstance(
			NetworkInfo.DetailedState.CONNECTED,
			ConnectivityManager.TYPE_WIFI,
			0,
			true,
			true
		)
		shadowConnectivityManager.addNetwork(network, networkInfo)
		shadowConnectivityManager.setNetworkCapabilities(network, caps)

		testDispatcher.scheduler.advanceUntilIdle()
		// Wait long enough for both settle delay and any potential restoration debounce
		advanceTimeBy(2100.milliseconds)
		testDispatcher.scheduler.advanceUntilIdle()

		assertEquals(
			Constants.DNS_MODE_HOSTNAME,
			Settings.Global.getString(app.contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		)
		assertEquals(
			hostname,
			Settings.Global.getString(app.contentResolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
		)
	}

	@Test
	fun restoration_whenDisconnected_restoresPreferredDns() = runTest(testDispatcher) {
		// Initial state: someone turned it off manually
		Settings.Global.putString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_MODE,
			Constants.DNS_MODE_OFF
		)

		// Advance time to let DnsToggleApplication.dnsObserver run and potentially overwrite PREF_PREFERRED_DNS_MODE
		testDispatcher.scheduler.advanceUntilIdle()

		val prefs = app.getPrefs()
		prefs.edit(commit = true) {
			putString(Constants.PREF_PREFERRED_DNS_MODE, Constants.DNS_MODE_OPPORTUNISTIC)
		}

		setupService()

		// To trigger onLost properly, we should have added it first.
		val network = ShadowNetwork.newInstance(3)
		val caps = ShadowNetworkCapabilities.newInstance()
		shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
		val networkInfo = ShadowNetworkInfo.newInstance(
			NetworkInfo.DetailedState.CONNECTED,
			ConnectivityManager.TYPE_WIFI,
			0,
			true,
			true
		)
		shadowConnectivityManager.addNetwork(network, networkInfo)
		shadowConnectivityManager.setNetworkCapabilities(network, caps)
		testDispatcher.scheduler.advanceUntilIdle()

		// Simulate network loss
		app.detectedSsid = null
		shadowConnectivityManager.removeNetwork(network)
		testDispatcher.scheduler.advanceUntilIdle()

		advanceTimeBy(Constants.WATCHDOG_RESTORE_DEBOUNCE_MS.milliseconds + 100.milliseconds)
		testDispatcher.scheduler.advanceUntilIdle()

		assertEquals(
			Constants.DNS_MODE_OPPORTUNISTIC,
			Settings.Global.getString(app.contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		)
	}
}
