package net.activitywatch.android.automation

import net.activitywatch.android.automation.LocalNetwork.HostKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LocalNetworkTest {

    private fun local(ip: String) = LocalNetwork.isLocalAddress(InetAddress.getByName(ip))

    @Test
    fun privateLoopbackAndLinkLocalAddressesAreLocal() {
        listOf(
            "127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.10",
            "169.254.10.1", "100.64.0.1", "100.127.255.254", "::1", "fe80::1", "fd12:3456::1",
        ).forEach { assertTrue("$it should be local", local(it)) }
    }

    @Test
    fun publicAddressesAreNotLocal() {
        listOf(
            "8.8.8.8", "1.1.1.1", "172.32.0.1", "172.15.255.255", "192.169.0.1",
            "100.63.255.255", "100.128.0.1", "11.0.0.1", "2001:4860:4860::8888",
        ).forEach { assertFalse("$it should not be local", local(it)) }
    }

    @Test
    fun classifiesIpLiteralsWithoutDns() {
        assertEquals(HostKind.LOCAL, LocalNetwork.classifyHost("192.168.1.10"))
        assertEquals(HostKind.LOCAL, LocalNetwork.classifyHost("[fd00::1]"))
        assertEquals(HostKind.PUBLIC, LocalNetwork.classifyHost("93.184.216.34"))
        // 2001:db8::/32 is documentation space, not local: treated like public.
        assertEquals(HostKind.PUBLIC, LocalNetwork.classifyHost("[2001:db8::1]"))
    }

    @Test
    fun classifiesConventionalLocalNames() {
        listOf("localhost", "homeassistant.local", "nas.lan", "printer.home.arpa", "hub", "pi.internal", "router.home.")
            .forEach { assertEquals(it, HostKind.LOCAL, LocalNetwork.classifyHost(it)) }
    }

    @Test
    fun otherNamesAreDecidedAtSendTime() {
        assertEquals(HostKind.UNKNOWN, LocalNetwork.classifyHost("example.com"))
        assertEquals(HostKind.UNKNOWN, LocalNetwork.classifyHost("nas.mydomain.org"))
        // Not fooled by a local-looking label in the middle of a public name.
        assertEquals(HostKind.UNKNOWN, LocalNetwork.classifyHost("local.example.com"))
    }

    @Test
    fun recognisesIpLiterals() {
        assertTrue(LocalNetwork.isIpLiteral("10.1.2.3"))
        assertTrue(LocalNetwork.isIpLiteral("[::1]"))
        assertFalse(LocalNetwork.isIpLiteral("homeassistant.local"))
        assertFalse(LocalNetwork.isIpLiteral("1.2.3"))
    }
}
