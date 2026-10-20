package com.lnight.valence.server.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

class ServerScopeGuardTest {
    private val paths = Konsist.scopeFromProduction().files.map { it.path.replace('\\', '/') }

    @Test
    fun `scope contains the server sources`() {
        assertTrue(paths.any { it.endsWith("/Application.kt") })
    }

    @Test
    fun `scope does not include android or protocol`() {
        val leaked = paths.filter { "/android/" in it || "/protocol/" in it }

        assertTrue(leaked.isEmpty(), "Unexpected files in scope: $leaked")
    }
}
