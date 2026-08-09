rootProject.name = "aegis-core"

// Service-per-database topology: each service is an independent deployable with its own
// PostgreSQL database. `contracts` is the only shared code — event schemas, DTOs and the
// Money value object — so that services never reach into each other's internals.
include("contracts")
include("policy-service")
include("rating-service")
include("claims-service")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
