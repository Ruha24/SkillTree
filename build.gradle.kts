plugins {
    java
}

group = property("projectGroup") as String
version = property("projectVersion") as String

val paperVersion = property("paperVersion") as String
val javaVersion = (property("javaVersion") as String).toInt()

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // PlaceholderAPI — необязательная зависимость (softdepend), только для компиляции.
    maven("https://repo.extendedclip.com/releases/")
    // Nexo — необязательная зависимость (иконки узлов из его предметов), только для компиляции.
    maven("https://repo.nexomc.com/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    // Движок экранов — отдельный плагин (depend: [ScreenUI]).
    compileOnly("com.screenui:ScreenUI:${property("screenUiVersion")}")
    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("com.nexomc:nexo:1.17.0") { isTransitive = false }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val tokens = mapOf("version" to version)
    inputs.properties(tokens)
    filesMatching("plugin.yml") {
        expand(tokens)
    }
}
