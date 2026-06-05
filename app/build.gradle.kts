plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.comiclab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.comiclab"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3.0605"

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
    kotlinOptions {
        jvmTarget = "11"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.sevenzipjbinding.android)
    implementation(libs.subsampling.scale.image.view)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.register("testClasses") {
    group = "verification"
    description = "Compatibility task for IDEs that request the JVM testClasses task on Android modules."

    dependsOn(
        tasks.matching {
            it.name == "compileDebugUnitTestKotlin" ||
                it.name == "compileDebugUnitTestJavaWithJavac"
        }
    )
}

tasks.configureEach {
    if (name.startsWith("lintVital")) {
        enabled = false
    }
}
