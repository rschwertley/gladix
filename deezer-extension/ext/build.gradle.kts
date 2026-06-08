plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    id("com.gradleup.shadow") version "9.0.2"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":common"))
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    compileOnly(libs.okhttp)
}

val extType = "music"
val extId = "deezer"
val extClass = "DeezerExtension"

val extIconUrl = "https://cdn-files.dzcdn.net/img/common/og-deezer-logo.png"
val extName = "Deezer"
val extDescription = "Deezer Extension for Echo."

val extAuthor = "Luftnos"
val extAuthorUrl = "https://github.com/LuftVerbot"

val extRepoUrl = "https://github.com/LuftVerbot/echo-deezer-extension"
val extUpdateUrl = "https://api.github.com/repos/LuftVerbot/echo-deezer-extension/releases"

val gitHash = execute("git", "rev-parse", "HEAD").take(7)
val gitCount = execute("git", "rev-list", "--count", "HEAD").toInt()
val verCode = gitCount
val verName = "v$gitHash"

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "dev.brahmkshatriya.echo.extension"
            artifactId = extId
            version = verName

            from(components["java"])
        }
    }
}

tasks {
    shadowJar {
        archiveBaseName.set(extId)
        archiveVersion.set(verName)
        manifest {
            attributes(
                mapOf(
                    "Extension-Id" to extId,
                    "Extension-Type" to extType,
                    "Extension-Class" to extClass,

                    "Extension-Version-Code" to verCode,
                    "Extension-Version-Name" to verName,

                    "Extension-Icon-Url" to extIconUrl,
                    "Extension-Name" to extName,
                    "Extension-Description" to extDescription,

                    "Extension-Author" to extAuthor,
                    "Extension-Author-Url" to extAuthorUrl,

                    "Extension-Repo-Url" to extRepoUrl,
                    "Extension-Update-Url" to extUpdateUrl
                )
            )
        }
    }
}

fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()
