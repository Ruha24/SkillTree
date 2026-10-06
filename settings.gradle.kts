plugins {
    // Lets Gradle auto-provision the JDK defined by the Java toolchain (java 25).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "SkillTree"

// Движок ScreenUI — отдельный проект. Лежит рядом (../ScreenUI) — собирается вместе с этим, правки в нём видны
// сразу; иначе берётся из mavenLocal (в проекте движка: ./gradlew publishToMavenLocal).
if (file("../ScreenUI/settings.gradle.kts").exists()) {
    includeBuild("../ScreenUI")
}
