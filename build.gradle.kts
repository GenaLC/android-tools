plugins {
    // AGP 8.7.x is the first line that officially supports compileSdk 35.
    // It requires Gradle 8.9+ (see gradle/wrapper/gradle-wrapper.properties)
    // and JDK 17.
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
