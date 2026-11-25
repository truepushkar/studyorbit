import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.stillpoint.focus"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.stillpoint.focus"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.9"
    }

    buildFeatures { compose = true }
    buildTypes {
        create("optimized") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

@CacheableTask
abstract class PackDomainCatalog : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val assetDirectory: DirectoryProperty

    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    @TaskAction
    fun pack() {
        val source = catalogDirectory.get().asFile
        val domains = source.listFiles().orEmpty()
            .filter { it.extension == "txt" && it.nameWithoutExtension.toIntOrNull() != null }
            .flatMap { it.readLines(Charsets.US_ASCII) }
            .sorted()
        check(domains.isNotEmpty() && domains.all { domain ->
            domain.isNotEmpty() && domain.all { it.code in 33..126 }
        }) { "The domain catalog must contain nonempty ASCII entries." }
        val archive = archiveFile.get().asFile
        archive.parentFile.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        ZipOutputStream(DigestOutputStream(archive.outputStream().buffered(), digest)).use { zip ->
            val starts = domains.indices step 4096
            zip.writeEntry("SOURCE.txt", source.resolve("SOURCE.txt").readBytes())
            zip.writeEntry("index.txt", starts.joinToString("\n", postfix = "\n") {
                domains[it]
            }.toByteArray(Charsets.US_ASCII))
            for ((id, start) in starts.withIndex()) {
                val end = minOf(start + 4096, domains.size)
                val block = domains.subList(start, end).joinToString("\n", postfix = "\n")
                zip.writeEntry("$id.txt", block.toByteArray(Charsets.US_ASCII))
            }
        }
        val metadata = assetDirectory.file("catalog-sha256.txt").get().asFile
        metadata.parentFile.mkdirs()
        metadata.writeText(HexFormat.of().formatHex(digest.digest()))
    }

    private fun ZipOutputStream.writeEntry(name: String, contents: ByteArray) {
        putNextEntry(ZipEntry(name).apply { time = 0 })
        write(contents)
        closeEntry()
    }
}

val packDownloadableDomainCatalog = tasks.register<PackDomainCatalog>("packDownloadableDomainCatalog") {
    catalogDirectory.set(layout.projectDirectory.dir("domain-catalog"))
    archiveFile.set(layout.buildDirectory.file("outputs/domain-catalog/StudyOrbit-domain-catalog.zip"))
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(packDownloadableDomainCatalog, PackDomainCatalog::assetDirectory)
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.foundation:foundation:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
}
