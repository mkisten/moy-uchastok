import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Google OAuth Client ID (тип «iOS/Installed», без секрета). Заполняется в gradle.properties:
//   googleClientId=1234567890-abc.apps.googleusercontent.com
val googleClientId = (project.findProperty("googleClientId") as String?) ?: ""

// Яндекс OAuth (вход через Яндекс ID). Ключи хранятся в local.properties (не публикуются):
//   yandexClientId=...
//   yandexClientSecret=...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val yandexClientId = localProps.getProperty("yandexClientId") ?: ""
val yandexClientSecret = localProps.getProperty("yandexClientSecret") ?: ""

android {
    namespace = "ru.obkhod.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.obkhod.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.3.7"
        vectorDrawables { useSupportLibrary = true }
        manifestPlaceholders["googleRedirectScheme"] = "com.googleusercontent.apps.$googleClientId"
        manifestPlaceholders["appAuthRedirectScheme"] = "com.googleusercontent.apps.$googleClientId"
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"$yandexClientId\"")
        buildConfigField("String", "YANDEX_CLIENT_SECRET", "\"$yandexClientSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release-сборка подписывается debug-ключом, чтобы APK можно было сразу установить (п. 25 ТЗ)
            signingConfig = signingConfigs.getByName("debug")
        }
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/metadata.kotlin_module", "META-INF/annotations.kotlin_module",
                "org/apache/poi/xssf/usermodel/.gitkeep"
            )
        }
        jniLibs { excludes += setOf("**/libjxl.so", "**/libgif7.so", "**/libwebp-sharp.so") }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("net.openid:appauth:0.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5") {
        exclude(group = "org.apache.xmlbeans", module = "xmlbeans")
    }
    implementation("org.apache.xmlbeans:xmlbeans:5.2.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
