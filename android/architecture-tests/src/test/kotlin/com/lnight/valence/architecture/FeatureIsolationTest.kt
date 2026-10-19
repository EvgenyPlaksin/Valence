package com.lnight.valence.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class FeatureIsolationTest {
    private val implPackage = Regex("""com\.lnight\.valence\.features\.([a-z]+)\.impl(\..+)?""")
    private val apiPackage = Regex("""com\.lnight\.valence\.features\.[a-z]+\.api(\..+)?""")

    @Test
    fun `feature implementations do not import other feature implementations`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                val ownFeature = featureOf(file.packagee?.name)
                ownFeature == null ||
                    file.imports.none { import ->
                        featureOf(import.name).let { it != null && it != ownFeature }
                    }
            }
    }

    @Test
    fun `feature api does not import any implementation`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                val isApi = apiPackage.matches(file.packagee?.name.orEmpty())
                !isApi || file.imports.none { implPackage.matches(it.name) }
            }
    }

    private fun featureOf(name: String?): String? = name?.let { implPackage.matchEntire(it)?.groupValues?.get(1) }
}
