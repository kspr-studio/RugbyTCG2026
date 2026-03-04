import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

fun quoteForBuildConfig(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val supabaseUrl = (localProps.getProperty("SUPABASE_URL") ?: "").trim()
val supabasePublishableKey = (localProps.getProperty("SUPABASE_PUBLISHABLE_KEY") ?: "").trim()

android {
    namespace = "com.roguegamestudio.rugbytcg"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += "wav"
    }

    defaultConfig {
        applicationId = "com.roguegamestudio.rugbytcg"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        buildConfigField("String", "SUPABASE_URL", quoteForBuildConfig(supabaseUrl))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quoteForBuildConfig(supabasePublishableKey))

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
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
