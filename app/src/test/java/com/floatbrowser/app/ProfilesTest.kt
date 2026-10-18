package com.floatbrowser.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    @Test fun contextIdUsesOnlySafeCharacters() {
        listOf("conta1", "minha conta", "ção/ñ:*?", "a-b_c").forEach {
            assertTrue(it, Regex("[A-Za-z0-9_]+").matches(ProfileStore.contextId(it)))
        }
    }

    @Test fun contextIdIsStable() =
        assertEquals(ProfileStore.contextId("conta1"), ProfileStore.contextId(" conta1 "))

    @Test fun differentNamesGetDifferentIds() {
        assertNotEquals(ProfileStore.contextId("a b"), ProfileStore.contextId("a_b"))
        assertNotEquals(ProfileStore.contextId("conta1"), ProfileStore.contextId("conta2"))
    }

    @Test fun profileContextNeverCollidesWithWindowContext() =
        assertTrue(ProfileStore.contextId("w1") != "ctx_w1")
}
