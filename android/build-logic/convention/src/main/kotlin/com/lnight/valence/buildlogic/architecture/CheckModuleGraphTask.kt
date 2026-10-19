package com.lnight.valence.buildlogic.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Reads the project model and produces no outputs")
abstract class CheckModuleGraphTask : DefaultTask() {
    @get:Input
    abstract val modules: ListProperty<ModuleDependencies>

    @TaskAction
    fun check() {
        val violations = ModuleGraphRules.violations(modules.get())
        if (violations.isNotEmpty()) {
            throw GradleException(
                violations.joinToString(separator = "\n", prefix = "Module graph violations:\n") { " - $it" },
            )
        }
    }
}
