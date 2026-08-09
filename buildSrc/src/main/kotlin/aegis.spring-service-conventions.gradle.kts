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

dependencies {
    implementation(project(":contracts"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Publishes build metadata to /actuator/info, so a running pod can be traced back to the
// exact commit that produced its image.
springBoot {
    buildInfo()
}
