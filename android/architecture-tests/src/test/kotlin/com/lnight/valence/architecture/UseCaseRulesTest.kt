package com.lnight.valence.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class UseCaseRulesTest {
    @Test
    fun `use cases reside in a domain package`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { !it.hasNameEndingWith("UseCase") || it.resideInPackage("..domain..") }
    }

    @Test
    fun `use cases expose a single public invoke operator`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { !it.hasNameEndingWith("UseCase") || it.hasSingleInvokeOperator() }
    }

    private fun KoClassDeclaration.hasSingleInvokeOperator(): Boolean {
        val publicFunctions = functions().filter { it.hasPublicOrDefaultModifier }
        return publicFunctions.size == 1 &&
            publicFunctions.single().let { it.name == "invoke" && it.hasOperatorModifier }
    }
}
