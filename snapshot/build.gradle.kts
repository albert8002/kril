// Build-time snapshot of the live Wikidata / Wikipedia lists.
// Reuses the app's pure parsing + ranking code, so CI and devices rank identically.
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

val shared = tasks.register<Sync>("syncSharedSources") {
    from(rootProject.file("composeApp/src/commonMain/kotlin")) {
        include("app/coredump/core/*.kt")
        include("app/coredump/data/WikidataTemplates.kt")
        include("app/coredump/data/WikipediaTemplates.kt")
        include("app/coredump/data/WikidataFamilies.kt")
        include("app/coredump/net/Mqtt.kt")
        include("app/coredump/net/Protocol.kt")
    }
    into(layout.buildDirectory.dir("shared-src"))
}

kotlin {
    sourceSets.named("main") { kotlin.srcDir(shared) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("app.coredump.snapshot.MainKt")
}
