/*
 * Applied by the three deployable services (policy, rating, claims). Everything a service
 * needs to be a well-behaved citizen of the platform — web layer, bean validation, actuator
 * health for Kubernetes probes, and the shared contracts module — is declared once here.
 */

plugins {
    id("aegis.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun library(alias: String): String = libs.findLibrary(alias).orElseThrow {
    IllegalStateException("Missing library '$alias' in gradle/libs.versions.toml")
}.get().toString()

dependencies {
    implementation(project(":contracts"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation(library("springdoc-openapi-webmvc"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Responses are validated against the committed OpenAPI document, so the spec cannot
    // silently drift away from what the service actually returns.
    testImplementation(library("swagger-request-validator-core")) {
        // swagger-request-validator depends on swagger-parser, which pulls the NON-jakarta
        // io.swagger.core.v3:swagger-core/swagger-models. springdoc pulls the -jakarta variants
        // of the same artifacts. Both publish identical io.swagger.v3.oas.models.* class names,
        // so the test runtime classpath ended up holding two copies of every model class, with
        // the classloader arbitrarily picking the older one — and /v3/api-docs died with
        // NoSuchMethodError: Schema.getDefaultSetFlag().
        //
        // Excluding the non-jakarta pair leaves exactly one copy on the classpath. Pinning both
        // families to the same version would also have worked, but duplicate classes remain a
        // trap for the next upgrade even when the versions happen to agree.
        exclude(group = "io.swagger.core.v3", module = "swagger-core")
        exclude(group = "io.swagger.core.v3", module = "swagger-models")
    }
}

// Publishes build metadata to /actuator/info, so a running pod can be traced back to the
// exact commit that produced its image.
springBoot {
    buildInfo()
}
