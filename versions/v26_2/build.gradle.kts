import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

paperweight {
    reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":core"))
    paperweight.paperDevBundle("26.2.build.124-stable")
}

// Publish the named JAR directly; Paper 26.2 no longer supplies reobf mappings.
configurations.create("mojangElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add("mojangElements", tasks.jar)
}
