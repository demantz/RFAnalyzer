package com.mantz_it.rfanalyzer.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * <h1>RF Analyzer - Local Network Access</h1>
 *
 * Module:      LocalNetworkAccess.kt
 * Description: Android 17's local network protection, for the two network sources (an rtl_tcp
 *              server, a KiwiSDR). Apps targeting Android 17 (API 37) reach addresses in the local
 *              network only with the runtime permission ACCESS_LOCAL_NETWORK (permission group
 *              "Nearby devices"); without it a TCP connection there simply times out. The internet
 *              stays open, so only a host in the local network needs it: MainActivity asks right
 *              before starting such a source, and a source error says what is missing
 *              (AnalyzerService). Android doesn't publish the address ranges it protects; these are
 *              the private, link-local and multicast ones. Loopback (rtl_tcp on the phone itself)
 *              is not asked for - should Android block it too, the error's hint still says why.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object LocalNetworkAccess {
    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    private const val ANDROID_17 = 37
    private const val RESOLVE_TIMEOUT_MS = 3000L

    /** Whether Android blocks this app from the local network right now (Android 17, no grant). */
    fun isBlocked(context: Context): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_17 && context.applicationInfo.targetSdkVersion >= ANDROID_17 &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED

    /** Whether [host] (a name or an address literal, as the user typed it) is in the local network.
     *  A name is resolved (on IO, with a timeout); one that doesn't resolve counts as not local -
     *  connecting then fails with its own error. */
    suspend fun isLocalHost(host: String): Boolean {
        val trimmed = host.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isEmpty()) return false
        if (isMdnsName(trimmed)) return true
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                runCatching { InetAddress.getAllByName(trimmed).any(::isLocalNetworkAddress) }.getOrDefault(false)
            } ?: false
        }
    }

    /** Without resolving anything (safe on the main thread): an address literal in the local
     *  network or an mDNS name. False for names that need DNS. */
    fun isLocalHostLiteral(host: String): Boolean {
        val trimmed = host.trim().removePrefix("[").removeSuffix("]")
        if (isMdnsName(trimmed)) return true
        if (!isAddressLiteral(trimmed)) return false
        return runCatching { isLocalNetworkAddress(InetAddress.getByName(trimmed)) }.getOrDefault(false)
    }

    /** ".local" names are resolved by multicast DNS - in the local network. */
    internal fun isMdnsName(host: String) = host.endsWith(".local", ignoreCase = true) || host.endsWith(".local.", ignoreCase = true)

    /** An IPv4 dotted quad or an IPv6 literal - InetAddress.getByName() parses these without DNS. */
    internal fun isAddressLiteral(host: String) = host.contains(':') || IPV4_LITERAL.matches(host)

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** Private (RFC 1918), link-local, unique local (fc00::/7) and multicast addresses, also as
     *  IPv4-mapped IPv6 - not loopback, not the internet. */
    internal fun isLocalNetworkAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return false
        if (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) return true
        if (address is Inet6Address) {
            val bytes = address.address
            if ((bytes[0].toInt() and 0xfe) == 0xfc) return true // unique local address
            // IPv4-mapped (::ffff:a.b.c.d): Java usually returns these as Inet4Address already
            if (bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte())
                return isLocalNetworkAddress(Inet4Address.getByAddress(bytes.copyOfRange(12, 16)))
        }
        return false
    }
}
