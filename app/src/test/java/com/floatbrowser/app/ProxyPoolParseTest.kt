package com.floatbrowser.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyPoolParseTest {
    private val pool = ProxyPool()

    @Test fun simpleHostPort() {
        val p = pool.parse("1.2.3.4:8080")!!
        assertEquals("1.2.3.4", p.host); assertEquals(8080, p.port)
        assertEquals("http", p.type); assertNull(p.user); assertNull(p.pass)
        assertEquals("http://1.2.3.4:8080", p.key())
    }

    @Test fun trimsWhitespace() = assertNotNull(pool.parse("  1.2.3.4:8080  "))

    @Test fun socksScheme() = assertEquals("socks5", pool.parse("socks5://1.2.3.4:1080")!!.type)

    @Test fun schemeIsLowercased() = assertEquals("http", pool.parse("HTTP://1.2.3.4:80")!!.type)

    @Test fun credentials() {
        val p = pool.parse("user:pass@1.2.3.4:3128")!!
        assertEquals("user", p.user); assertEquals("pass", p.pass)
        assertEquals("1.2.3.4", p.host); assertEquals(3128, p.port)
    }

    @Test fun schemeAndCredentialsAndHostname() {
        val p = pool.parse("http://u:p@host.example.com:80")!!
        assertEquals("host.example.com", p.host); assertEquals("u", p.user)
    }

    @Test fun rejectsGarbage() {
        assertNull(pool.parse("garbage"))
        assertNull(pool.parse(""))
        assertNull(pool.parse("1.2.3.4"))
    }

    @Test fun rejectsInvalidPorts() {
        assertNull(pool.parse("1.2.3.4:99999"))
        assertNull(pool.parse("1.2.3.4:00"))
        assertNull(pool.parse("1.2.3.4:8"))
    }

    @Test fun ipKeyPrefersMeasuredExitIp() {
        val p = pool.parse("1.2.3.4:8080")!!
        assertEquals("1.2.3.4", p.ipKey())
        assertEquals("9.9.9.9", p.copy(exitIp = "9.9.9.9").ipKey())
    }
}
