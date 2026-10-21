package com.lnight.valence.server.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

class LayerRulesTest {
    private val domainAllowedImports =
        listOf(
            Regex("""kotlin\..+"""),
            Regex("""kotlinx\.coroutines(\..+)?"""),
            Regex("""java\.time(\..+)?"""),
            Regex("""java\.nio\.ByteBuffer"""),
            Regex("""java\.security\.MessageDigest"""),
            Regex("""com\.lnight\.valence\.server\.domain(\..+)?"""),
        )

    @Test
    fun `domain imports only framework free dependencies`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                !file.inLayer("domain") ||
                    file.imports.all { import -> domainAllowedImports.any { it.matches(import.name) } }
            }
    }

    @Test
    fun `routes do not import the store`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                !file.inLayer("routes") ||
                    file.imports.none { it.name.startsWith("com.lnight.valence.server.store") }
            }
    }

    @Test
    fun `store imports neither ktor nor routes`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                !file.inLayer("store") ||
                    file.imports.none {
                        it.name.startsWith("io.ktor.") || it.name.startsWith("com.lnight.valence.server.routes")
                    }
            }
    }

    @Test
    fun `libsignal is imported only inside the crypto layer`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file -> file.inLayer("crypto") || file.imports.none { it.name.startsWith("org.signal.") } }
    }

    private fun KoFileDeclaration.inLayer(layer: String): Boolean {
        val base = "com.lnight.valence.server.$layer"
        val name = packagee?.name.orEmpty()
        return name == base || name.startsWith("$base.")
    }
}
