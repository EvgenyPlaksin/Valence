package com.lnight.valence.buildlogic.architecture

internal object ModuleGraphRules {
    private const val SIGNAL_GROUP = "org.signal"
    private const val PROTOCOL = "com.lnight.valence:protocol"

    private val coreFoundation = setOf(":core:model", ":core:common")
    private val coreInfrastructure = setOf(":core:crypto", ":core:database", ":core:network")
    private val pureGroups = setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx")
    private val featureApi = Regex("""^:features:[a-z-]+:api$""")
    private val featureImpl = Regex("""^:features:[a-z-]+:impl$""")

    private class Policy(
        val description: String,
        val allows: (String) -> Boolean,
    )

    fun violations(modules: List<ModuleDependencies>): List<String> =
        modules.flatMap { module ->
            val policy = policyFor(module.path)
            if (policy == null) {
                listOf("${module.path} has no layering rule; add one to ModuleGraphRules")
            } else {
                projectViolations(module, policy) + externalViolations(module)
            }
        }

    private fun projectViolations(
        module: ModuleDependencies,
        policy: Policy,
    ): List<String> =
        module.projects
            .filterNot(policy.allows)
            .sorted()
            .map { "${module.path} must not depend on $it; it ${policy.description}" }

    private fun externalViolations(module: ModuleDependencies): List<String> {
        val path = module.path
        val signal =
            if (path == ":core:crypto") {
                emptyList()
            } else {
                module.externals
                    .filter { it.startsWith("$SIGNAL_GROUP:") }
                    .map { "$path must not depend on $it; libsignal is confined to :core:crypto" }
            }
        val protocol =
            if (path == ":core:network" || PROTOCOL !in module.externals) {
                emptyList()
            } else {
                listOf("$path must not depend on the protocol module; only :core:network may")
            }
        val impure =
            if (isPure(path)) {
                module.externals
                    .filterNot { it.substringBefore(':') in pureGroups }
                    .map { "$path must not depend on $it; it may use only Kotlin and kotlinx libraries" }
            } else {
                emptyList()
            }
        return signal + protocol + impure
    }

    private fun isPure(path: String): Boolean =
        path == ":core:model" || path == ":core:common" || featureApi.matches(path)

    private fun policyFor(path: String): Policy? =
        when {
            path == ":app" -> {
                Policy("is the composition root and may depend on anything") { true }
            }

            path == ":architecture-tests" -> {
                Policy("must not depend on other modules") { false }
            }

            path == ":core:model" -> {
                Policy("must not depend on other modules") { false }
            }

            path == ":core:ui-theme" -> {
                Policy("must not depend on other modules") { false }
            }

            path == ":core:common" -> {
                Policy("may depend only on :core:model") { it == ":core:model" }
            }

            path in coreInfrastructure -> {
                Policy("may depend only on :core:model and :core:common") { it in coreFoundation }
            }

            path == ":core:data" -> {
                Policy("may depend only on foundation and infrastructure core modules") {
                    it in coreFoundation || it in coreInfrastructure
                }
            }

            featureApi.matches(path) -> {
                Policy("may depend only on :core:model and :core:common") { it in coreFoundation }
            }

            featureImpl.matches(path) -> {
                Policy("may depend only on feature api modules, :core:model, :core:common and :core:ui-theme") {
                    it in coreFoundation || it == ":core:ui-theme" || featureApi.matches(it)
                }
            }

            else -> {
                null
            }
        }
}
