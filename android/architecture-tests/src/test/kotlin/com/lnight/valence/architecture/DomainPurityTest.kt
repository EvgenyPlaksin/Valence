package com.lnight.valence.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class DomainPurityTest {
    private val domainPackage = Regex("""com\.lnight\.valence\.features\.([a-z]+)\.impl\.domain(\..+)?""")
    private val allowedImports =
        listOf(
            Regex("""kotlin\..+"""),
            Regex("""kotlinx\.coroutines(\..+)?"""),
            Regex("""com\.lnight\.valence\.core\.(model|common)(\..+)?"""),
            Regex("""com\.lnight\.valence\.features\.[a-z]+\.api(\..+)?"""),
            Regex("""javax\.inject\.Inject"""),
        )

    @Test
    fun `domain packages import only allowed dependencies`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                val feature = domainPackage.matchEntire(file.packagee?.name.orEmpty())?.groupValues?.get(1)
                feature == null ||
                    file.imports.all { import ->
                        allowedImports.any { it.matches(import.name) } ||
                            import.name.startsWith("com.lnight.valence.features.$feature.impl.domain")
                    }
            }
    }
}
