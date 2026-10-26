plugins {
    id("com.android.application")
}

// URL du backend par défaut. Avec `adb reverse tcp:3000 tcp:3000`, la tablette
// joint le backend du Mac via le câble USB, sans dépendre du WiFi.
// Surchargeable : ./gradlew installDebug -PbackendUrl=http://192.168.1.42:3000
val backendUrl = (findProperty("backendUrl") as String?) ?: "http://127.0.0.1:3000"

// Code technicien (appui long sur le titre de Réglages) : montre la carte Kiosque. -PtechCode=… pour le changer.
val techCode = (findProperty("techCode") as String?) ?: "4242"

// Adresse courte du logiciel de l'ordinateur, affichée au premier lancement (à taper dans le navigateur de l'ordinateur).
val installUrl = (findProperty("installUrl") as String?) ?: "unowhy.com/compagnon"

android {
    namespace = "com.unowhy.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.unowhy.companion"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "TECH_CODE", "\"$techCode\"")
        buildConfigField("String", "INSTALL_URL", "\"$installUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // Tests JVM : android.util.Log et consorts renvoient des valeurs par défaut au lieu de lever une exception.
    // Erreurs de lint déjà présentes figées dans lint-baseline.xml : seules les nouvelles bloquent la CI.
    lint {
        baseline = file("lint-baseline.xml")
    }

    // Tests instrumentés (app/src/androidTest/, émulateur API 29) : animations coupées, sinon Espresso attend les boucles.
    testOptions {
        unitTests.isReturnDefaultValues = true
        animationsDisabled = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Vraie implémentation de org.json : celle d'android.jar n'est qu'un bouchon sur la JVM.
    testImplementation("org.json:json:20250517")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
