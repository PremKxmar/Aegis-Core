dependencyResolutionManagement {
    repositories {
        // The SpotBugs plugin is published to the Gradle Plugin Portal only, so buildSrc needs
        // both repositories to resolve the plugin marker artefacts.
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
