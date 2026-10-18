package com.floatbrowser.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPoolAssignTest {
    private val pool = ProxyPool()
    private fun p(s: String) = pool.parse(s)!!

    @Test fun exactProxyIsNotSharedBetweenWindows() {
        assertTrue(pool.assignExact("w1", p("1.1.1.1:80")))
        assertFalse(pool.assignExact("w2", p("1.1.1.1:80")))
        assertTrue(pool.assignExact("w2", p("2.2.2.2:80")))
        assertEquals("2.2.2.2", pool.current("w2")!!.host)
    }

    @Test fun sameWindowCanReassign() {
        assertTrue(pool.assignExact("w1", p("1.1.1.1:80")))
        assertTrue(pool.assignExact("w1", p("1.1.1.1:80")))
    }

    @Test fun releaseFreesTheIp() {
        assertTrue(pool.assignExact("w1", p("1.1.1.1:80")))
        pool.release("w1")
        assertTrue(pool.assignExact("w2", p("1.1.1.1:80")))
    }

    @Test fun burnedProxyIsRefused() {
        assertTrue(pool.assignExact("w1", p("1.1.1.1:80")))
        pool.burn("w1")
        assertFalse(pool.assignExact("w1", p("1.1.1.1:80")))
    }
}
