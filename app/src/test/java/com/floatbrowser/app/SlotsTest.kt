package com.floatbrowser.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotsTest {
    @Test fun menorVagaLivre() {
        assertEquals(1, Slots.pick(emptySet(), 4))
        assertEquals(2, Slots.pick(setOf(1), 4))
        assertEquals(1, Slots.pick(setOf(2, 3), 4))   // vaga 1 fechada: a próxima janela volta a ser a W1
    }
    @Test fun reabreNaMesmaVaga() {
        assertEquals(3, Slots.pick(setOf(1), 4, want = 3))
        assertEquals(2, Slots.pick(setOf(1, 3), 4, want = 3))   // vaga pedida ocupada: usa a livre
    }
    @Test fun semVaga() {
        assertEquals(0, Slots.pick(setOf(1, 2, 3, 4), 4))
    }
    @Test fun contextoEstavel() {
        assertEquals("ctx_slot_2", Slots.contextId(2))
    }
}
