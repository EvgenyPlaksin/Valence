package com.lnight.valence.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class LayerBoundariesTest {
    private val dataLayerPrefixes =
        listOf(
            "com.lnight.valence.core.data.",
            "com.lnight.valence.core.database.",
            "com.lnight.valence.core.network.",
            "com.lnight.valence.core.crypto.",
            "androidx.room.",
            "io.ktor.",
            "okhttp3.",
        )

    @Test
    fun `libsignal is imported only inside core crypto`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                val insideCrypto =
                    file.packagee
                        ?.name
                        .orEmpty()
                        .startsWith("com.lnight.valence.core.crypto")
                insideCrypto || file.imports.none { it.name.startsWith("org.signal.") }
            }
    }

    @Test
    fun `view models do not import data layer packages`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                file.classes().none { it.hasNameEndingWith("ViewModel") } ||
                    file.imports.none { import -> dataLayerPrefixes.any { import.name.startsWith(it) } }
            }
    }
}
