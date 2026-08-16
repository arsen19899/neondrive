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
        versionCode = 7
        versionName = "1.5.0"

        // Голосовое управление приносит в сборку нативные библиотеки Vosk —
        // единственные native-.so во всём проекте. По умолчанию Gradle упаковал
        // бы их под все четыре архитектуры и добавил к APK около сорока
        // мегабайт, три четверти которых на магнитоле никогда не выполнятся:
        // головные устройства бывают только ARM. Оставляем две реально нужные.
        //
        // Побочный эффект, о котором стоит знать: сборка перестанет запускаться
        // на x86-эмуляторе. Для проверки на эмуляторе достаточно временно
        // добавить сюда "x86_64".
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
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

    // Собственная карта на рабочем столе (режим «Своя карта»).
    // osmdroid рисует тайлы OpenStreetMap обычным Canvas — без ключа, без GMS и
    // без нагрузки на GPU, что важно для ГУ уровня Cortex-A53 + PowerVR. Это
    // единственный способ показать настоящую карту НА ЧАСТИ экрана, не завися от
    // freeform-режима прошивки: карта рисуется внутри нашего же окна.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Офлайн-карта: рендеринг векторных карт mapsforge прямо на устройстве.
    //
    // В отличие от офлайн-графа маршрутов ниже, эта зависимость включена
    // всерьёз, а не закомментирована. Причина в разнице цены: GraphHopper тянет
    // Jackson, JTS и protobuf, а здесь — чистый рендерер на Canvas, тот же
    // подход, что у самого osmdroid, без GPU и без сторонних тяжёлых форматов.
    //
    // Что это даёт: вся Беларусь одним файлом на 304 МБ вместо шестидесяти
    // гигабайт растровых тайлов на тот же зум — см. nav/OfflineMap.kt.
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20")

    // ── Офлайн-маршрутизация ─────────────────────────────────────────────────
    // Маршруты без интернета по заранее собранному графу — см.
    // nav/OfflineRouter.kt и build-graph.bat, который этот граф собирает.
    //
    // Зависимость тяжёлая и это осознанная плата: GraphHopper тянет Jackson,
    // JTS и protobuf, заметно увеличивает APK и количество методов в dex. Если
    // сборка упрётся в лимит методов или ГУ начнёт задыхаться — строку можно
    // закомментировать обратно, ничего больше не меняя: связь с библиотекой
    // сделана рефлексией, и без зависимости офлайн-роутер честно отвечает
    // «недоступен», а маршруты идут через сеть, как раньше.
    //
    // Версия здесь и версия graphhopper-web, которой собирался граф, обязаны
    // совпадать: формат графа между версиями несовместим. build-graph.ps1
    // прибит к тем же 1.0 — меняя одно, меняйте и другое.
    implementation("com.graphhopper:graphhopper-core:1.0")

    // ── Голосовое управление «Елисей» ────────────────────────────────────────
    // Офлайн-распознавание речи. Включено всерьёз, а не закомментировано, по той
    // же логике, что и mapsforge выше: без него ключевое слово «Елисей» не
    // работает вовсе, а системный android.speech.SpeechRecognizer на магнитолах
    // без сервисов Google отсутствует — то есть голосовое управление на половине
    // устройств не завелось бы никогда.
    //
    // Цена честная и её нужно знать: это единственная нативная зависимость
    // проекта, около 10 МБ на архитектуру. Отсюда abiFilters выше.
    //
    // Связь с библиотекой — рефлексивная (voice/VoskEngine.kt), ровно как с
    // GraphHopper ниже. Поэтому строку можно закомментировать: оболочка
    // соберётся и будет работать, потеряв только ожидание ключевого слова —
    // кнопка руля и кнопка микрофона уйдут на системный распознаватель.
    //
    // Сама модель языка в APK НЕ входит и входить не может: русская модель
    // весит около 45 МБ. Она кладётся на устройство отдельно, как карта и граф
    // маршрутов, — см. README.
    // ВАЖНО: строки две, и обе с @aar — иначе голос не заводится на устройстве.
    //
    // POM vosk-android объявляет своей зависимостью net.java.dev.jna:jna:4.4.0
    // в виде ОБЫЧНОГО JAR. На Android этот jar бесполезен: в нём нет
    // libjnidispatch.so ни под одну ABI, а именно через него JNA дёргает
    // нативную часть Vosk. Сборка при этом проходит без единого предупреждения,
    // и всё падает только на живом ГУ, в момент создания org.vosk.Model:
    // статическая инициализация JNA не находит свою нативную библиотеку.
    //
    // Поэтому vosk берём как @aar (Gradle тогда не тянет транзитивный jar), а
    // JNA подкладываем сами в aar-варианте — в нём .so лежат в jniLibs и
    // нормально попадают в APK. Ровно так это сделано в официальном демо
    // vosk-android; версия JNA с версией vosk не связана.
    implementation("com.alphacephei:vosk-android:0.3.45@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")
}
