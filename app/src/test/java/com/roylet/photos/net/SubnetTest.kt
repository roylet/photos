package com.roylet.photos.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetTest {
    @Test fun `a slash 24 yields every host but the network, broadcast and self`() {
        val hosts = Subnet.scanHosts("192.168.1.37", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.37" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
    }

    @Test fun `bigger subnets are capped to the slash 24 around the device`() {
        val hosts = Subnet.scanHosts("10.4.200.9", 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.4.200.") })
        assertEquals(253, Subnet.scanHosts("172.20.3.3", 8).size)
    }

    @Test fun `smaller subnets stay small`() {
        assertEquals(listOf("192.168.1.129", "192.168.1.130", "192.168.1.131", "192.168.1.133", "192.168.1.134"), Subnet.scanHosts("192.168.1.132", 29))
        assertEquals(listOf("10.0.0.2"), Subnet.scanHosts("10.0.0.1", 30))
        assertTrue(Subnet.scanHosts("10.0.0.1", 31).isEmpty())
        assertTrue(Subnet.scanHosts("10.0.0.1", 32).isEmpty())
    }

    @Test fun `addresses above 128 dot work despite signed ints`() {
        val hosts = Subnet.scanHosts("200.100.50.1", 24)
        assertEquals("200.100.50.2", hosts.first())
        assertEquals("200.100.50.254", hosts.last())
        assertEquals("255.255.255.255", Subnet.format(Subnet.parse("255.255.255.255")!!))
    }

    @Test fun `bad input yields nothing`() {
        assertNull(Subnet.parse("192.168.1"))
        assertNull(Subnet.parse("192.168.1.256"))
        assertNull(Subnet.parse("a.b.c.d"))
        assertNull(Subnet.parse("1..2.3"))
        assertTrue(Subnet.scanHosts("fe80::1", 64).isEmpty())
    }

    @Test fun `network key and private ranges`() {
        assertEquals("192.168.1.0", Subnet.networkOf("192.168.1.37", 24))
        assertEquals(LanInfo("192.168.1.37", 24, "192.168.1.1").key, LanInfo("192.168.1.99", 24, "192.168.1.1").key)
        for (h in listOf("10.0.0.5", "172.16.0.1", "172.31.255.1", "192.168.0.10", "100.100.1.1", "nas.local", "127.0.0.1")) assertTrue(h, Subnet.isPrivateHost(h))
        for (h in listOf("172.32.0.1", "8.8.8.8", "photos.example.com")) assertFalse(h, Subnet.isPrivateHost(h))
    }

    @Test fun `candidates put hints and the gateway first, without duplicates`() {
        val scanner = LanScanner()
        val c = scanner.candidates(LanInfo("192.168.1.37", 24, "192.168.1.1"), listOf("http://192.168.1.20:2283/"))
        assertEquals(listOf("http://192.168.1.20:2283", "http://192.168.1.1:2283", "http://192.168.1.2:2283"), c.take(3))
        assertEquals(253, c.size)
    }
}
