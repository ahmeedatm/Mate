plugins {
    id("com.android.application") version "9.4.1" apply false
}

// ktlint sans plugin Gradle : règles dans .editorconfig. ./gradlew ktlintCheck (CI), ./gradlew ktlintFormat (corrige).
val ktlint: Configuration by configurations.creating

dependencies {
    ktlint("com.pinterest.ktlint:ktlint-cli:1.8.0") {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) }
    }
}

val ktlintFiles = listOf("app/src/**/*.kt", "**/*.kts", "!**/build/**")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Vérifie le style du code Kotlin."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(listOf("--relative") + ktlintFiles)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Corrige le style du code Kotlin."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args(listOf("--relative", "--format") + ktlintFiles)
}
