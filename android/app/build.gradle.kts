plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kannadadoc"
    compileSdk = 35

    defaultConfig {
        val versionFile = rootProject.file("../VERSION")
        val resolvedVersion = if (versionFile.exists()) {
            versionFile.readText().trim().ifBlank { "0.19.0" }
        } else {
            "0.19.0"
        }
        val verParts = resolvedVersion.split(".").mapNotNull { it.toIntOrNull() }
        val maj = verParts.getOrElse(0) { 0 }
        val min = verParts.getOrElse(1) { 19 }
        val pat = verParts.getOrElse(2) { 0 }

        applicationId = "com.kannadadoc"
        minSdk = 28
        targetSdk = 35
        versionCode = maj * 10000 + min * 100 + pat
        versionName = resolvedVersion
        resValue("string", "app_version", resolvedVersion)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Phase 2: Binary Word 97-2003 (.doc) parser ONLY (excludes bloated poi-ooxml & xmlbeans)
    implementation(libs.poi.core) {
        exclude(group = "org.apache.xmlbeans")
    }
    implementation(libs.poi.scratchpad) {
        exclude(group = "org.apache.xmlbeans")
    }

    // Phase 4: PDF text extraction with font-dictionary awareness
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.ui.tooling)
}
