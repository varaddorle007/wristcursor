import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.drift.receiver.MainKt")
    applicationName = "drift-receiver"
}

// Single runnable jar: `java -jar receiver/build/libs/drift-receiver.jar`
tasks.jar {
    archiveFileName.set("drift-receiver.jar")
    manifest { attributes("Main-Class" to "com.drift.receiver.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
