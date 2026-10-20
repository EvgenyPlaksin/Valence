package com.lnight.valence.protocol.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

class DtoRulesTest {
    private val bodySuffixes = listOf("Request", "Response", "Frame")
    private val wireSuffixes = bodySuffixes + "Dto"

    @Test
    fun `wire types are serializable`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { !it.isWireType() || it.hasAnnotationWithName("Serializable") }
    }

    @Test
    fun `wire types are data classes or enums`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { !it.isWireType() || it.hasDataModifier || it.hasEnumModifier }
    }

    @Test
    fun `wire types have immutable properties`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { !it.isWireType() || it.properties().all { property -> property.hasValModifier } }
    }

    @Test
    fun `wire types follow the naming convention`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { declaration ->
                !declaration.isWireType() ||
                    declaration.hasEnumModifier ||
                    wireSuffixes.any { declaration.hasNameEndingWith(it) }
            }
    }

    @Test
    fun `bodies and frames carry the protocol version`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { declaration ->
                val isBody = declaration.isWireType() && bodySuffixes.any { declaration.hasNameEndingWith(it) }
                !isBody || declaration.properties().any { it.name == "v" && it.type?.name == "Int" }
            }
    }

    @Test
    fun `frames declare their wire type name`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertTrue { declaration ->
                val isFrame =
                    declaration.hasParentWithName("ServerFrame") || declaration.hasParentWithName("ClientFrame")
                !isFrame || declaration.hasAnnotationWithName("SerialName")
            }
    }

    private fun KoClassDeclaration.isWireType(): Boolean =
        resideInPackage("..protocol.http..") || resideInPackage("..protocol.frame..")
}
