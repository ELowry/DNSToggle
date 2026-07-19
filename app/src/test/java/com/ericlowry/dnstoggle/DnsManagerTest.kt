package com.ericlowry.dnstoggle

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsManager
import com.ericlowry.dnstoggle.data.DnsSettingsRepository
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
	fun autoBlacklistDrivenToggleOff_doesNotOverwritePreferredDnsMode() {
		// Regression test: turning DNS off because the current SSID is auto-blacklisted must
		// not clobber the user's global preference, or leaving that SSID later would restore
		// the wrong mode (the bug this test guards against).
		val prefs = app.getPrefs()
		prefs.edit {
			putBoolean(Constants.PREF_AUTO_BLACKLIST, true)
			putString(Constants.PREF_PREFERRED_DNS_MODE, Constants.DNS_MODE_HOSTNAME)
		}
		app.detectedSsid = "TestSSID"

		DnsManager.togglePrivateDns(app, enabled = false)

		assertEquals(
			Constants.DNS_MODE_HOSTNAME,
			prefs.getString(Constants.PREF_PREFERRED_DNS_MODE, null)
		)
		assertEquals(setOf("TestSSID"), DnsSettingsRepository.blacklist.value)
	}
}
