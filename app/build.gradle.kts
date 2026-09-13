import groovy.json.JsonSlurper
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cbkres.visavole"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cbkres.visavole"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0-alpha01"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    aaptOptions {
        // Keep the bundled SQLite DB stored (uncompressed) so it can be opened via a file
        // descriptor and its size compared on launch to detect bundled-data refreshes.
        noCompress("db")
        // Keep the binary world geometry uncompressed so first launch can read it without APK
        // decompression.
        noCompress("bin")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.register("updateVisaDb") {
    group = "visadb"
    description = "Fetch the latest VisaDB release and refresh the bundled database when its SHA-256 changes."
    val assetFile = File(projectDir, "src/main/assets/visa_data.db")
    val shaFile = File(projectDir, "src/main/assets/visa_data.db.sha256")
    outputs.file(assetFile)
    outputs.file(shaFile)
    outputs.upToDateWhen { false }
    doLast {
        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun githubToken(): String? = System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN")

        fun githubGet(url: String): HttpURLConnection {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.setRequestProperty("User-Agent", "VisaVole-Gradle")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val token = githubToken()
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                error("VisaDB download failed: HTTP $status for $url")
            }
            return connection
        }

        fun githubRead(url: String): String =
            githubGet(url).inputStream.use { it.readBytes().decodeToString() }

        fun githubDownload(url: String, destination: File) {
            githubGet(url).inputStream.use { input ->
                destination.outputStream().use { input.copyTo(it) }
            }
        }

        fun sha256FromSums(sums: String, fileName: String): String? =
            sums.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it[1] == fileName }
                ?.get(0)
                ?.lowercase()

        val repo = "The-Omni-Stuart/VisaDB"
        val releaseJson = githubRead("https://api.github.com/repos/$repo/releases/latest")
        val release = JsonSlurper().parseText(releaseJson) as Map<*, *>
        val assets: List<*> = when (val rawAssets = release["assets"]) {
            is List<*> -> rawAssets
            else -> emptyList<Any>()
        }

        fun assetUrl(name: String): String? =
            (assets.firstOrNull { (it as? Map<*, *>)?.get("name") == name } as? Map<*, *>)
                ?.get("browser_download_url") as? String

        val dbUrl = assetUrl("visa_data.db")
            ?: error("Latest VisaDB release has no visa_data.db asset")
        val sumsUrl = assetUrl("SHA256SUMS")
        val remoteSha = sumsUrl?.let { sha256FromSums(githubRead(it), "visa_data.db") }
        val currentSha = assetFile.takeIf { it.exists() }?.let { sha256Hex(it) }

        if (currentSha == null || remoteSha == null || currentSha != remoteSha) {
            val temp = File.createTempFile("visa_data", ".db")
            try {
                githubDownload(dbUrl, temp)
                val downloadedSha = sha256Hex(temp)
                if (remoteSha != null && downloadedSha != remoteSha) {
                    error("VisaDB download failed SHA check: expected $remoteSha, got $downloadedSha")
                }
                assetFile.parentFile?.mkdirs()
                temp.copyTo(assetFile, overwrite = true)
                shaFile.writeText("$downloadedSha\n")
                logger.lifecycle("VisaDB: updated ${assetFile.name} to latest release SHA $downloadedSha")
            } finally {
                temp.delete()
            }
        } else {
            val sidecarSha = shaFile.takeIf { it.exists() }?.readText()?.trim()
            if (sidecarSha != remoteSha) {
                shaFile.writeText("$remoteSha\n")
                logger.lifecycle("VisaDB: refreshed SHA sidecar for current ${assetFile.name}")
            } else {
                logger.lifecycle("VisaDB: ${assetFile.name} is already at latest release SHA $remoteSha")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("updateVisaDb")
}

tasks.withType<Test>().configureEach {
    dependsOn("updateVisaDb")
}
