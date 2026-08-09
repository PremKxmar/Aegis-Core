plugins {
    id("aegis.jpa-conventions")
}

description = "Rating: deterministic premium calculation from effective-dated rate tables, with an explainable worksheet."

dependencies {
    // Property-based testing. The rating engine's guarantees — monotonic in coverage limit,
    // never below the floor, worksheet reconciles to the total — are universally quantified
    // statements, and jqwik tests them by generating hundreds of risks rather than by asserting
    // three hand-picked ones.
    testImplementation(libs.jqwik)
}
