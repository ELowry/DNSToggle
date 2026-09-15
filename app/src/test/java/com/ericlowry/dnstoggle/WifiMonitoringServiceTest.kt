package com.ericlowry.dnstoggle

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.CurrentNetwork
import com.ericlowry.dnstoggle.data.DnsPolicyEvaluator
import com.ericlowry.dnstoggle.data.repository.DnsSettingsRepository
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.SecurityRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import com.ericlowry.dnstoggle.service.ConnectivityWatchdogManager
import com.ericlowry.dnstoggle.service.WifiMonitoringService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.util.ReflectionHelpers
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = DnsToggleApplication::class, sdk = [34])
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

		// Clear everything to ensure a clean start
		app.getPrefs().edit(commit = true) { clear() }
		app.getEncryptedPrefs().edit(commit = true) { clear() }

		SecurityRepository.initialize()
		VpnRepository.initialize(app)
		NetworkProfileRepository.initialize(app)
		HostnameRepository.initialize(app)
		DnsSettingsRepository.initialize(app)

		// Wait for background initialization to settle.
		runBlocking {
			withTimeoutOrNull(3000.milliseconds) {
				while (NetworkProfileRepository.networkProfiles.value == null) {
					delay(50.milliseconds)
				}
			}
		}
		testDispatcher.scheduler.advanceUntilIdle()
		shadowOf(Looper.getMainLooper()).idle()

		// Reset DnsPolicyEvaluator internal state
		resetDnsPolicyEvaluator()

		app.getPrefs().edit(commit = true) {
			remove(Constants.PREF_IS_IN_VPN_OVERRIDE)
			remove(Constants.PREF_ACTIVE_SSID_OVERRIDE)
			putString(Constants.PREF_PREFERRED_DNS_MODE, Constants.DNS_MODE_OFF)
			putString(Constants.PREF_DEFAULT_OFF_MODE, Constants.DNS_MODE_OFF)
		}
		app.detectedSsid = null

		Settings.Global.putString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_MODE,
			Constants.DNS_MODE_OFF
		)
		Settings.Global.putString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_SPECIFIER,
			null
		)

		// Grant common permissions
		shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
		shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
		shadowOf(app).grantPermissions(Manifest.permission.ACCESS_WIFI_STATE)
		shadowOf(app).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)

		connectivityManager =
			app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
		shadowConnectivityManager = shadowOf(connectivityManager)
		shadowOf(Looper.getMainLooper()).idle()
	}

	private fun resetDnsPolicyEvaluator() {
		ReflectionHelpers.setStaticField(DnsPolicyEvaluator::class.java, "isTransitioning", false)
		val settleJob =
			ReflectionHelpers.getStaticField<Job?>(DnsPolicyEvaluator::class.java, "dnsSettleJob")
		settleJob?.cancel()
		ReflectionHelpers.setStaticField(DnsPolicyEvaluator::class.java, "dnsSettleJob", null)
		ReflectionHelpers.setStaticField(DnsPolicyEvaluator::class.java, "lastBssid", null)
		ReflectionHelpers.setStaticField(DnsPolicyEvaluator::class.java, "lastNotifiedSsid", null)
		ReflectionHelpers.setStaticField(
			DnsPolicyEvaluator::class.java,
			"hasShownLocationWarning",
			false
		)
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun setupService(): WifiMonitoringService {
		val controller = Robolectric.buildService(WifiMonitoringService::class.java)
		val service = controller.get()
		service.mainDispatcher = testDispatcher
		controller.create().startCommand(0, 0)
		testDispatcher.scheduler.advanceUntilIdle()
		return service
	}

	@Test
	fun vpnConnected_withVpnOverrideEnabled_appliesVpnDns() = runTest(testDispatcher) {
		VpnRepository.updateVpnOverrideEnabled(true)
		VpnRepository.updateVpnDns(Constants.DNS_MODE_HOSTNAME, "vpn.dns.com")

		val network = ShadowNetwork.newInstance(1)
		val caps = ShadowNetworkCapabilities.newInstance()
		shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
		shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

		val networkInfo = ShadowNetworkInfo.newInstance(
			NetworkInfo.DetailedState.CONNECTED,
			ConnectivityManager.TYPE_VPN,
			0,
			true,
			true
		)
		shadowConnectivityManager.addNetwork(network, networkInfo)
		shadowConnectivityManager.setNetworkCapabilities(network, caps)
		shadowOf(Looper.getMainLooper()).idle()

		// Setting preferred mode to off to ensure we can distinguish restoration from override
		app.getPrefs().edit(commit = true) {
			putString(Constants.PREF_PREFERRED_DNS_MODE, Constants.DNS_MODE_OFF)
		}
		Settings.Global.putString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_MODE,
			Constants.DNS_MODE_OFF
		)

		// Directly evaluate policy to bypass tracker issues in Robolectric
		DnsPolicyEvaluator.evaluate(
			app,
			CoroutineScope(testDispatcher + SupervisorJob()),
			CurrentNetwork(isVpnActive = true),
			mapOf(network to caps),
			ConnectivityWatchdogManager(app, CoroutineScope(testDispatcher + SupervisorJob())),
			Constants.DNS_MODE_OFF
		)

		// Settle and evaluate
		testDispatcher.scheduler.advanceUntilIdle()
		shadowOf(Looper.getMainLooper()).idle()
		// Wait long enough for settle delay (0.5s)
		advanceTimeBy(1000.milliseconds)
		testDispatcher.scheduler.advanceUntilIdle()
		shadowOf(Looper.getMainLooper()).idle()

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

		val wifiInfo = ShadowWifiInfo.newInstance()
		shadowOf(wifiInfo).setSSID("\"HomeWifi\"")
		shadowOf(wifiInfo).setBSSID("00:11:22:33:44:55")

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
		shadowOf(Looper.getMainLooper()).idle()

		// Directly evaluate policy
		DnsPolicyEvaluator.evaluate(
			app,
			CoroutineScope(testDispatcher + SupervisorJob()),
			CurrentNetwork(
				ssid = ssid,
				bssid = "00:11:22:33:44:55",
				isValidated = true,
				hasInternet = true,
				wifiCapabilities = caps
			),
			mapOf(network to caps),
			ConnectivityWatchdogManager(app, CoroutineScope(testDispatcher + SupervisorJob())),
			Constants.DNS_MODE_OFF
		)

		testDispatcher.scheduler.advanceUntilIdle()
		shadowOf(Looper.getMainLooper()).idle()
		// Wait long enough for both settle delay (0.5s) and any potential restoration debounce (2s)
		advanceTimeBy(3000.milliseconds)
		testDispatcher.scheduler.advanceUntilIdle()
		shadowOf(Looper.getMainLooper()).idle()

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
