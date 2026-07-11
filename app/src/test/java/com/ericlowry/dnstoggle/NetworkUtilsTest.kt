package com.ericlowry.dnstoggle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

	@Test
	fun stripSsidQuotes_removesQuotes() {
		assertEquals("MyHomeNetwork", "\"MyHomeNetwork\"".stripSsidQuotes())
		assertEquals("MyHomeNetwork", "MyHomeNetwork\"".stripSsidQuotes())
		assertEquals("MyHomeNetwork", "\"MyHomeNetwork".stripSsidQuotes())
		assertEquals("MyHomeNetwork", "MyHomeNetwork".stripSsidQuotes())
		assertEquals("", "\"\"".stripSsidQuotes())
		assertEquals("", "".stripSsidQuotes())
	}

	@Test
	fun isValidDnsHostname_validatesCorrectly() {
		assertTrue(NetworkUtils.isValidDnsHostname("dns.google"))
		assertTrue(NetworkUtils.isValidDnsHostname("dns.nextdns.io"))
		assertTrue(NetworkUtils.isValidDnsHostname("1.1.1.1.cloudflare-dns.com"))
		assertTrue(NetworkUtils.isValidDnsHostname("localhost"))
		assertTrue(NetworkUtils.isValidDnsHostname("a.b.c.d.e.f.g"))

		assertFalse(NetworkUtils.isValidDnsHostname(""))
		assertFalse(NetworkUtils.isValidDnsHostname("invalid_dns"))
		assertFalse(NetworkUtils.isValidDnsHostname("-dns.google"))
		assertFalse(NetworkUtils.isValidDnsHostname("dns.google-"))
		assertFalse(NetworkUtils.isValidDnsHostname("dns..google"))
		assertFalse(NetworkUtils.isValidDnsHostname("a".repeat(254)))
	}
}
