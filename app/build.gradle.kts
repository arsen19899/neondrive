plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.neondrive.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neondrive.launcher"
        // Головные устройства на Android 6+ (большинство китайских ГУ — 8.1/10/12)
        minSdk = 23
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.1"
    }

    signingConfigs {
        // Постоянный ключ релиза — специально закоммичен в репозиторий вместе с
        // паролями. Это НЕ секрет в обычном смысле: у ключа нет доступа ни к
        // чему, кроме права переустановки этого конкретного пакета поверх самого
        // себя. Раньше release-сборка подписывалась автосгенерированным
        // debug.keystore — на GitHub Actions это отдельная, каждый раз новая
        // виртуалка без сохранённого ~/.android/debug.keystore, поэтому КАЖДЫЙ
        // прогон workflow подписывал APK новым случайным ключом. Android не
        // ставит обновление поверх приложения с другой подписью («приложение не
        // установлено» / конфликт пакета), поэтому пользователю приходилось
        // вручную удалять NeonDrive перед установкой каждой новой сборки.
        // Единый файл ключа, лежащий в репозитории, даёт всем сборкам —
        // локальным и через Actions — одну и ту же подпись, и обновления
        // ставятся поверх старой версии как обычно.
        create("release") {
            storeFile = file("../keystore/neondrive-release.jks")
            storePassword = "neondrive_release_2026"
            keyAlias = "neondrive"
            keyPassword = "neondrive_release_2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Без суффикса: лаунчер должен ставиться одним пакетом,
            // иначе система предложит выбрать «домашний экран» дважды.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        // Оболочка намеренно лезет в системные вещи (телефония, sysfs, чужие
        // медиасессии), поэтому предупреждения линтера не должны валить сборку.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    // Владельцы жизненного цикла для Compose в окнах WindowManager
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3 / ExoPlayer — локальные файлы, HLS/ICY-радиопотоки
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media:media:1.7.0")

    // Хранилище настроек
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // SAF-доступ к папкам на USB/SD, когда прошивка ГУ не индексирует их в MediaStore
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Загрузка обложек / иконок
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")
}
