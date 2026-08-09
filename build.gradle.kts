/*
 * The root project is intentionally almost empty. Build policy lives in the convention
 * plugins under buildSrc/, applied explicitly by each module, rather than being injected
 * into children with a `subprojects {}` block. Explicit application means you can read a
 * module's build file and know what it does without reading the root build file too.
 */

tasks.register("printModules") {
    group = "help"
    description = "Lists the modules that make up the Aegis Core platform."
    val names = subprojects.map { it.path }.sorted()
    doLast { names.forEach { println(it) } }
}
