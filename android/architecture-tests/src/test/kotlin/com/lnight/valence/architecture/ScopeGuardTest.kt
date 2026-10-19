package com.lnight.valence.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeGuardTest {
    private val files = Konsist.scopeFromProduction().files

    @Test
    fun `scope contains the android application`() {
        assertTrue(files.any { it.name == "MainActivity" })
    }

    @Test
    fun `scope does not include server or protocol`() {
        val leaked =
            files
                .map { it.path.replace('\\', '/') }
                .filter { "/server/" in it || "/protocol/" in it }

        assertTrue("Unexpected files in scope: $leaked", leaked.isEmpty())
    }
}
