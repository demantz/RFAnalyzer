package com.mantz_it.rfanalyzer.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * <h1>RF Analyzer - Local Network Access Tests</h1>
 *
 * Module:      TestLocalNetworkAccess.kt
 * Description: Which hosts need Android 17's local network permission (LocalNetworkAccess).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestLocalNetworkAccess {

    private fun local(literal: String) = LocalNetworkAccess.isLocalNetworkAddress(InetAddress.getByName(literal))

    @Test
    fun privateLinkLocalAndMulticastAddressesAreLocal() {
        for (address in listOf("10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.10.1", "224.0.0.251",
                "fe80::1", "fd12:3456::1", "fc00::1", "ff02::fb", "::ffff:192.168.1.20"))
            assertTrue(address, local(address))
    }

    @Test
    fun loopbackAndTheInternetAreNot() {
        for (address in listOf("127.0.0.1", "::1", "0.0.0.0", "8.8.8.8", "172.32.0.1", "172.15.255.255", "100.64.0.1",
                "2001:4860:4860::8888", "::ffff:8.8.8.8"))
            assertFalse(address, local(address))
    }

    @Test
    fun literalsAndMdnsNamesNeedNoLookup() {
        assertTrue(LocalNetworkAccess.isLocalHostLiteral("192.168.1.20"))
        assertTrue(LocalNetworkAccess.isLocalHostLiteral(" 10.20.30.40 "))
        assertTrue(LocalNetworkAccess.isLocalHostLiteral("[fe80::1]"))
        assertTrue(LocalNetworkAccess.isLocalHostLiteral("raspberrypi.local"))
        assertTrue(LocalNetworkAccess.isLocalHostLiteral("kiwisdr.LOCAL."))
        assertFalse(LocalNetworkAccess.isLocalHostLiteral("127.0.0.1"))
        assertFalse(LocalNetworkAccess.isLocalHostLiteral("8.8.8.8"))
        // a name needs DNS: not decided here
        assertFalse(LocalNetworkAccess.isLocalHostLiteral("kiwi.example.org"))
        assertFalse(LocalNetworkAccess.isLocalHostLiteral(""))
    }

    @Test
    fun hostsResolveToTheirAddresses() = runBlocking {
        assertTrue(LocalNetworkAccess.isLocalHost("192.168.0.10"))
        assertTrue(LocalNetworkAccess.isLocalHost("myradio.local"))
        assertFalse(LocalNetworkAccess.isLocalHost("127.0.0.1"))
        assertFalse(LocalNetworkAccess.isLocalHost(""))
        // "localhost" resolves to loopback without a DNS server
        assertFalse(LocalNetworkAccess.isLocalHost("localhost"))
    }
}
