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

    @Test fun candidatesSkipAvoidedTakenAndQuarantined() {
        pool.manual.addAll(listOf(p("1.1.1.1:80"), p("2.2.2.2:80"), p("3.3.3.3:80"), p("4.4.4.4:80")))
        assertTrue(pool.assignExact("w1", p("1.1.1.1:80")))   // w1 usa 1.1.1.1
        assertTrue(pool.assignExact("w2", p("2.2.2.2:80")))   // w2 usa 2.2.2.2
        assertTrue(pool.assignExact("w9", p("3.3.3.3:80")))
        pool.burn("w9")                                        // 3.3.3.3 em quarentena
        val hosts = pool.candidates("w1", avoid = "1.1.1.1", limit = 6).map { it.host }
        assertEquals(listOf("4.4.4.4"), hosts)
    }

    @Test fun candidatesAllowOwnCurrentWhenNotAvoided() {
        pool.manual.addAll(listOf(p("1.1.1.1:80"), p("2.2.2.2:80")))
        assertTrue(pool.assignExact("w1", p("1.1.1.1:80")))
        assertEquals(listOf("1.1.1.1", "2.2.2.2"), pool.candidates("w1", null, 6).map { it.host })
    }

    @Test fun candidatesRespectLimit() {
        pool.manual.addAll(listOf(p("1.1.1.1:80"), p("2.2.2.2:80"), p("3.3.3.3:80")))
        assertEquals(1, pool.candidates("w1", null, 1).size)
    }

    @Test fun burnedProxyIsRefused() {
        assertTrue(pool.assignExact("w1", p("1.1.1.1:80")))
        pool.burn("w1")
        assertFalse(pool.assignExact("w1", p("1.1.1.1:80")))
    }
}
