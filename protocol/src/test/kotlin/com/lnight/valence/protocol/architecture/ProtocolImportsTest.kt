package com.lnight.valence.protocol.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

class ProtocolImportsTest {
    private val allowedImports =
        listOf(
            Regex("""kotlin\..+"""),
            Regex("""kotlinx\.serialization(\..+)?"""),
            Regex("""java\.util\.Base64"""),
            Regex("""java\.nio\.ByteBuffer"""),
            Regex("""com\.lnight\.valence\.protocol(\..+)?"""),
        )

    @Test
    fun `protocol imports only kotlin serialization and itself`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file -> file.imports.all { import -> allowedImports.any { it.matches(import.name) } } }
    }
}
