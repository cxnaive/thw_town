plugins { java }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.124-stable")
    compileOnly(files(providers.gradleProperty("dominionJar").getOrElse("../../../build/libs/Dominion-5.2.6-release-full.jar")))
}
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
