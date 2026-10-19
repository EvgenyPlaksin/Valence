package com.lnight.valence.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class InjectionRulesTest {
    private val injectableSuffixes =
        listOf(
            "UseCase",
            "ViewModel",
            "Repository",
            "RepositoryImpl",
            "DataSource",
            "Mapper",
        )

    @Test
    fun `constructor injection is limited to the injectable class types`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { declaration ->
                val injected =
                    declaration.primaryConstructor?.hasAnnotationWithName("Inject") == true ||
                        declaration.constructors.any { it.hasAnnotationWithName("Inject") }
                !injected || injectableSuffixes.any { declaration.hasNameEndingWith(it) }
            }
    }

    @Test
    fun `field injection is limited to android entry points`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { declaration ->
                val fieldInjected = declaration.properties().any { it.hasAnnotationWithName("Inject") }
                !fieldInjected ||
                    declaration.hasAnnotationWithName("AndroidEntryPoint") ||
                    declaration.hasAnnotationWithName("HiltAndroidApp")
            }
    }

    @Test
    fun `view models depend only on use cases and saved state`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { declaration ->
                !declaration.hasNameEndingWith("ViewModel") ||
                    declaration.primaryConstructor?.parameters.orEmpty().all { parameter ->
                        parameter.type.name.endsWith("UseCase") || parameter.type.name == "SavedStateHandle"
                    }
            }
    }
}
