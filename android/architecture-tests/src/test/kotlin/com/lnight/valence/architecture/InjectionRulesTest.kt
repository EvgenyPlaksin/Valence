package com.lnight.valence.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class InjectionRulesTest {
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
    fun `mappers are injected classes`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertTrue { file ->
                val staticMapper = file.objects().any { it.hasNameEndingWith("Mapper") }
                val abstractMapper = file.interfaces().any { it.hasNameEndingWith("Mapper") }
                val uninjectedMapper =
                    file.classes().any { declaration ->
                        declaration.hasNameEndingWith("Mapper") &&
                            declaration.constructors.none { it.hasAnnotationWithName("Inject") }
                    }
                !staticMapper && !abstractMapper && !uninjectedMapper
            }
    }
}
