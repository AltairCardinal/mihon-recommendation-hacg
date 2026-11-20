plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("com.diffplug.spotless") version "8.3.0"
}

group = "org.mihon.recommendation"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly("org.mihon:recommendation-api:1.1.0")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation("org.mihon:recommendation-api:1.1.0")
    testImplementation("org.mihon:recommendation-rules:1.0.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

val pluginJar by tasks.registering(Jar::class) {
    archiveBaseName.set("mihon-recommendation-hacg")
    archiveClassifier.set("plugin")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from("LICENSE") { into("META-INF") }
    from({
        configurations.runtimeClasspath
            .get()
            .filterNot {
                it.name.startsWith("kotlin-stdlib") || it.name.startsWith("kotlinx-coroutines") ||
                    it.name.startsWith("annotations-")
            }.map(::zipTree)
    })
    exclude(
        "META-INF/*.SF",
        "META-INF/*.RSA",
        "META-INF/*.DSA",
        "META-INF/INDEX.LIST",
        "module-info.class",
        "META-INF/versions/**/module-info.class",
    )
}

tasks.test {
    useJUnitPlatform { excludeTags("live-network") }
    dependsOn(pluginJar)
    systemProperty(
        "hacg.pluginJar",
        pluginJar
            .get()
            .archiveFile
            .get()
            .asFile.absolutePath,
    )
}

spotless {
    kotlin {
        target("src/**/*.kt", "*.kts")
        ktlint("1.8.0")
        endWithNewline()
    }
}


// Data-only rules are signed independently from the legacy executable plugin.
val rulesJar by tasks.registering(Jar::class) {
    archiveBaseName.set("mihon-subscription-hacg")
    archiveClassifier.set("rules")
    from("rules")
    from("LICENSE")
}
