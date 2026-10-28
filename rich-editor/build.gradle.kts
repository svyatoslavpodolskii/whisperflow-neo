plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mohamedrejeb.richeditor.compose"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material")
    implementation(libs.androidx.compose.material3)
    implementation("com.mohamedrejeb.ksoup:ksoup-html:0.4.0")
    implementation("com.mohamedrejeb.ksoup:ksoup-entities:0.4.0")
    implementation("org.jetbrains:markdown:0.7.3")
}
