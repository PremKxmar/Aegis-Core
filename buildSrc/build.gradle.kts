plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.spring.boot)
    implementation(libs.plugin.spring.dependency.management)
    implementation(libs.plugin.spotless)
    implementation(libs.plugin.spotbugs)
}
