import org.gradle.api.tasks.Delete

plugins {
    // Versions are defined in settings.gradle.kts via pluginManagement
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}