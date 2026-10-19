// Compiles the Android app's Kotlin against Robolectric's android-all (Android's framework
// classes, from Maven Central) and runs the JVM unit tests, without the Android SDK.
// The real app build is ../ (Android Gradle plugin); this exists for CI and quick checks.
rootProject.name = "orb-android-typecheck"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
