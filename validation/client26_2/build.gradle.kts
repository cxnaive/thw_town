plugins { application }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-snapshots/") {
        metadataSources { mavenPom(); artifact() }
    }
    maven("https://repo.opencollab.dev/maven-releases/")
}
dependencies {
    implementation("org.geysermc.mcprotocollib:protocol:26.2-20260907.143312-19")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass.set("cn.lunadeer.validation.NativeClient") }
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
