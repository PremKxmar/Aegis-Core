/*
 * Persistence, applied only by services that own a database.
 *
 * Kept separate from `aegis.spring-service-conventions` so that a service without a schema does
 * not inherit a DataSource it cannot configure — Spring Boot's JPA auto-configuration fails
 * fast when there is no database to connect to, which is correct behaviour and exactly why the
 * dependency should not be handed out by default.
 */

plugins {
    id("aegis.spring-service-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Flyway owns the schema; Hibernate's ddl-auto is `validate` in every service, so entities
    // drifting from the migrations is a startup failure rather than a column-not-found error on
    // whichever request first touches the new field.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Real PostgreSQL in tests. See PostgresIntegrationTest for why H2 is not an option here.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
