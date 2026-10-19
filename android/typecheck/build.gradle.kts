plugins {
    kotlin("jvm") version "2.0.21"
}

java { targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

sourceSets {
    main { kotlin.srcDir("../app/src/main/java") }
    test { kotlin.srcDir("../app/src/test/java") }
}

val androidAll = "org.robolectric:android-all:15-robolectric-12650502"

dependencies {
    compileOnly(androidAll)
    testImplementation(androidAll) // real org.json for the tests
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
