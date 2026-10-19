package com.lnight.valence.buildlogic.architecture

import java.io.Serializable

data class ModuleDependencies(
    val path: String,
    val projects: Set<String>,
    val externals: Set<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
