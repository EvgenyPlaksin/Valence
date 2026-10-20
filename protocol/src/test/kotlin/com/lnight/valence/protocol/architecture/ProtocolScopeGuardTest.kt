package com.lnight.valence.protocol.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

class ProtocolScopeGuardTest {
    private val paths = Konsist.scopeFromProduction().files.map { it.path.replace('\\', '/') }

    @Test
    fun `scope contains the protocol sources`() {
        assertTrue(paths.any { it.endsWith("/ProtocolJson.kt") })
    }

    @Test
    fun `scope does not include android or server`() {
        val leaked = paths.filter { "/android/" in it || "/server/" in it }

        assertTrue(leaked.isEmpty(), "Unexpected files in scope: $leaked")
    }
}
