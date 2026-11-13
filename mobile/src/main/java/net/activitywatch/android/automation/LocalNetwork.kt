package net.activitywatch.android.automation

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Plain http:// requests from automations are only allowed to the local network (like a
 * home-automation hub at http://192.168.1.10:8123 or http://homeassistant.local:8123);
 * anything on the internet must use https://.
 *
 * The platform cleartext policy (network_security_config) cannot express IP ranges, so it
 * permits cleartext and this is the gate. The authoritative check runs at send time on
 * the *resolved* addresses ([isLocalAddress]); the hostname heuristics only give the
 * editor early feedback.
 *
 * Pure logic (java.net only, no DNS for literals), so it is unit-tested on the JVM.
 */
object LocalNetwork {

    /**
     * True for loopback, private (RFC 1918), link-local, CGNAT/shared (100.64.0.0/10,
     * which is also what Tailscale and similar VPNs hand out) and IPv6 unique-local
     * addresses. Never true for a public address.
     */
    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val b = address.address
        return when (address) {
            is Inet4Address -> {
                val first = b[0].toInt() and 0xff
                val second = b[1].toInt() and 0xff
                first == 100 && second in 64..127 // 100.64.0.0/10
            }
            // fc00::/7 unique local (isSiteLocalAddress only covers the deprecated fec0::/10).
            is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
    }

    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** True when [host] is an IP address written out, so it can be checked without DNS. */
    fun isIpLiteral(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        return IPV4_LITERAL.matches(h) || (h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' })
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".intranet")

    /**
     * Editor-time classification of an http:// host:
     *  - LOCAL: a local IP literal, localhost, a single-label name (e.g. "nas") or a
     *    conventional local suffix (.local, .lan, .home.arpa, ...);
     *  - PUBLIC: an IP literal outside the local ranges (always refused);
     *  - UNKNOWN: any other name; allowed only if it resolves to a local address when sent
     *    (e.g. split-horizon DNS for nas.example.com).
     */
    enum class HostKind { LOCAL, PUBLIC, UNKNOWN }

    fun classifyHost(host: String): HostKind {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return HostKind.UNKNOWN
        if (isIpLiteral(h)) {
            val address = try {
                // A literal is parsed, never looked up.
                InetAddress.getByName(h.removePrefix("[").removeSuffix("]"))
            } catch (e: Exception) {
                return HostKind.UNKNOWN
            }
            return if (isLocalAddress(address)) HostKind.LOCAL else HostKind.PUBLIC
        }
        if (h == "localhost" || !h.contains('.') || LOCAL_SUFFIXES.any { h.endsWith(it) }) return HostKind.LOCAL
        return HostKind.UNKNOWN
    }
}
