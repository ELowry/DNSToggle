package com.ericlowry.dnstoggle

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsManager
import com.ericlowry.dnstoggle.data.repository.DnsSettingsRepository
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.SecurityRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = DnsToggleApplication::class, sdk = [34])
class DnsManagerTest {

	private lateinit var app: DnsToggleApplication

	@Before
	fun setup() {
		app = ApplicationProvider.getApplicationContext()
		SecurityRepository.initialize(app)
		VpnRepository.initialize(app)
		NetworkProfileRepository.initialize(app)
		HostnameRepository.initialize(app)
		DnsSettingsRepository.initialize(app)
		app.detectedSsid = null
	}

	@Test
	fun manualToggleOn_updatesPreferredDnsMode() {
		app.getPrefs()
			.edit { putString(Constants.PREF_PREFERRED_DNS_MODE, Constants.DNS_MODE_OPPORTUNISTIC) }

		DnsManager.togglePrivateDns(app, enabled = true, targetHostname = "dns.google")

		assertEquals(
			Constants.DNS_MODE_HOSTNAME,
			app.getPrefs().getString(Constants.PREF_PREFERRED_DNS_MODE, null)
		)
	}

	@Test
	fun manualToggleOff_updatesPreferredDnsMode() {
		app.getPrefs()
			.edit { putString(Constants.PREF_PREFERRED_DNS_MODE, Constants.DNS_MODE_HOSTNAME) }

		DnsManager.togglePrivateDns(app, enabled = false)

		assertEquals(
			Constants.DNS_MODE_OPPORTUNISTIC,
			app.getPrefs().getString(Constants.PREF_PREFERRED_DNS_MODE, null)
		)
	}

	@Test
	fun autoSaveStateDrivenToggleOff_doesNotOverwritePreferredDnsMode() {
		val prefs = app.getPrefs()
		prefs.edit {
			putBoolean(Constants.PREF_AUTO_SAVE_STATE, true)
			putString(Constants.PREF_PREFERRED_DNS_MODE, Constants.DNS_MODE_HOSTNAME)
		}
		app.detectedSsid = "TestSSID"

		DnsManager.togglePrivateDns(app, enabled = false, isFromTile = true)

		assertEquals(
			Constants.DNS_MODE_HOSTNAME,
			prefs.getString(Constants.PREF_PREFERRED_DNS_MODE, null)
		)
		val profile = NetworkProfileRepository.networkProfiles.value?.find { it.ssid == "TestSSID" }
		assertEquals(false, profile?.isEnabled)
	}
}
