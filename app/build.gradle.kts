import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}
val eodigajiApiKey: String = localProperties.getProperty("EODIGAJI_API_KEY") ?: ""
val rawApiBaseUrl: String = localProperties.getProperty("EODIGAJI_API_BASE_URL")?.trim() ?: ""
val eodigajiApiBaseUrl: String = if (rawApiBaseUrl.isNotBlank() && !rawApiBaseUrl.endsWith("/")) {
    "$rawApiBaseUrl/"
} else {
    rawApiBaseUrl
}

val apiKeySalt: ByteArray = byteArrayOf(
    0x57.toByte(), 0x3A.toByte(), 0x7F.toByte(), 0x22.toByte(),
    0x4E.toByte(), 0x69.toByte(), 0x1B.toByte(), 0x54.toByte(),
    0x2C.toByte(), 0x3D.toByte(), 0x71.toByte(), 0x48.toByte(),
    0x63.toByte(), 0x1A.toByte(), 0x5D.toByte(), 0x29.toByte()
)

fun obfuscateApiKey(key: String, salt: ByteArray): ByteArray {
    val bytes = key.toByteArray(Charsets.UTF_8)
    return ByteArray(bytes.size) { i ->
        val s = salt[i % salt.size].toInt()
        val b = bytes[i].toInt()
        ((b xor s) xor ((i * 7) and 0xFF)).toByte()
    }
}

val obfuscatedApiKeyJava = obfuscateApiKey(eodigajiApiKey, apiKeySalt)
    .joinToString(separator = ", ") { it.toString() }
val apiKeySaltJava = apiKeySalt
    .joinToString(separator = ", ") { it.toString() }

android {
    namespace = "com.example.eodigaji"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.eodigaji"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("byte[]", "API_KEY_ENC", "new byte[] { $obfuscatedApiKeyJava }")
        buildConfigField("byte[]", "API_KEY_SALT", "new byte[] { $apiKeySaltJava }")
        buildConfigField("String", "EODIGAJI_API_BASE_URL", "\"$eodigajiApiBaseUrl\"")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.pleos.navi.helper)
    implementation(libs.pleos.vehicle)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.glide)
    implementation(libs.androidx.security.crypto)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}