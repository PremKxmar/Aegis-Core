plugins {
    id("aegis.java-conventions")
    `java-library`
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        // Import the Boot BOM without applying the Spring Boot plugin: `contracts` is a plain
        // library, not a deployable, so it must not produce a bootJar. The BOM still gives us
        // Boot's tested versions for Jackson, JUnit and AssertJ.
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    // Jackson annotations only (not databind): the contracts module describes the wire shape
    // of DTOs and events. Choosing an ObjectMapper is the consuming service's business.
    api("com.fasterxml.jackson.core:jackson-annotations")

    // Persistence annotations are compile-only so that Money can be an @Embeddable in service
    // entities without dragging Hibernate onto the classpath of anything that just reads events.
    compileOnly("jakarta.persistence:jakarta.persistence-api")
    // Also needed when compiling tests: javac reads the annotations off Money.class and warns
    // about every attribute it cannot resolve if the API is absent from the test classpath.
    testCompileOnly("jakarta.persistence:jakarta.persistence-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
