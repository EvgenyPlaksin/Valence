package com.lnight.valence.buildlogic.architecture

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

object ModuleDependencyReader {
    private val productionConfiguration =
        Regex(
            "^(api|implementation|compileOnly|runtimeOnly)$" +
                "|^(?!test|androidTest).*(Api|Implementation|CompileOnly|RuntimeOnly)$",
        )

    fun read(project: Project): ModuleDependencies {
        val dependencies =
            project.configurations
                .matching { productionConfiguration.matches(it.name) }
                .flatMap { it.dependencies }

        return ModuleDependencies(
            path = project.path,
            projects = dependencies.filterIsInstance<ProjectDependency>().map { it.path }.toSet(),
            externals =
                dependencies
                    .filterIsInstance<ExternalModuleDependency>()
                    .map { "${it.group}:${it.name}" }
                    .toSet(),
        )
    }
}
