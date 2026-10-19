import com.lnight.valence.buildlogic.architecture.CheckModuleGraphTask
import com.lnight.valence.buildlogic.architecture.ModuleDependencyReader

tasks.register<CheckModuleGraphTask>("checkModuleGraph") {
    group = "verification"
    description = "Verifies the module dependency rules of the Android build"
    modules.set(
        provider {
            rootProject.subprojects
                .filter { it.buildFile.exists() }
                .map { ModuleDependencyReader.read(it) }
        },
    )
}
