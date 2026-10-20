package com.floatbrowser.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptStoreTest {
    @Test fun framesHeaderIsDetected() {
        assertTrue(ScriptStore.wantsFrames("// @name x\n// @match https://a.com/*\n// @frames\ncodigo()"))
        assertTrue(ScriptStore.wantsFrames("  //   @frames all\ncodigo()"))
    }

    @Test fun scriptsWithoutTheHeaderStayInTheMainFrame() {
        assertFalse(ScriptStore.wantsFrames("// @name x\n// @match https://a.com/*\ncodigo()"))
        assertFalse(ScriptStore.wantsFrames("const s = '// @frames';"))
        assertFalse(ScriptStore.wantsFrames("// @framesx\n"))
    }
}
