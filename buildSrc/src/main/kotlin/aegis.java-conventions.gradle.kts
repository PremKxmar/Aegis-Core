import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask

/*
 * Baseline build policy shared by every module: Java 21 toolchain, formatting, static
 * analysis, and a JaCoCo coverage gate wired into `check` so that `./gradlew build` fails
 * locally for exactly the same reasons it fails in CI.
 */

plugins {
    java
    jacoco
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun version(alias: String): String = libs.findVersion(alias).orElseThrow {
    IllegalStateException("Missing version '$alias' in gradle/libs.versions.toml")
}.requiredVersion

group = "com.aegis"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(version("java").toInt())
    }
}

dependencies {
    // Compile-only: @SuppressFBWarnings is a build-time concern and must not leak into the
    // runtime classpath of a deployed service.
    compileOnly("com.github.spotbugs:spotbugs-annotations:${version("spotbugsTool")}")
    testCompileOnly("com.github.spotbugs:spotbugs-annotations:${version("spotbugsTool")}")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -parameters keeps constructor and method parameter names in the class file. Spring's
    // @PathVariable/@RequestParam binding and Jackson record deserialisation both rely on it,
    // and the failures without it are confusing runtime errors rather than compile errors.
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Tests fork their own JVM, so -D flags on the Gradle command line do not reach them.
    // Forward the specific ones that drive test behaviour rather than passing everything, which
    // would let an unrelated local property silently change what the suite does.
    listOf("updateOpenApi").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(version("palantirJavaFormat"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

spotbugs {
    toolVersion = version("spotbugsTool")
    effort = Effort.MAX
    // HIGH confidence only. A static-analysis gate that cries wolf gets disabled by the next
    // developer; one that only reports near-certain defects stays switched on.
    reportLevel = Confidence.HIGH
    excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
}

/*
 * SpotBugs runs in its own JVM off its own `spotbugs` configuration. In the service modules the
 * `io.spring.dependency-management` plugin applies the Spring Boot BOM to *every* configuration
 * in the project, and that includes this one — so Boot's opinion about commons-lang3 (3.17.0)
 * silently downgraded the 3.20.0 that SpotBugs 4.10 resolves for itself. The BCEL bytecode
 * library inside SpotBugs calls org.apache.commons.lang3.Strings, which only exists from 3.18.0,
 * so the analyser died with NoClassDefFoundError on any class containing an LDC instruction —
 * reported only as the near-useless "SpotBugs ended with exit code 4".
 *
 * The analyser's classpath is build infrastructure, not shipped code, so the application BOM has
 * no business constraining it. Restoring each dependency's originally requested version is done
 * inside afterEvaluate so this rule is registered after dependency-management's own.
 */
afterEvaluate {
    configurations.named("spotbugs").configure {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
                useVersion(requested.version ?: return@eachDependency)
            }
        }
    }
}

tasks.withType<SpotBugsTask>().configureEach {
    reports.register("html") { required = true }
    reports.register("xml") { required = true }
}

// Static analysis of test sources produces mostly noise (unread fields on fixtures, etc.).
tasks.named<SpotBugsTask>("spotbugsTest") { enabled = false }

jacoco {
    toolVersion = version("jacoco")
}

// Classes excluded from coverage measurement, with the reason for each:
//
//   Spring Boot entry points  - the `main` method is a framework handoff with no branching
//                               logic. Covering it measures nothing and inflates the number.
//   @Configuration classes    - declarative bean wiring, exercised transitively by every
//                               integration test that starts a context.
val coverageExclusions = listOf("**/*Application.class", "**/config/**")

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }),
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }),
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}
