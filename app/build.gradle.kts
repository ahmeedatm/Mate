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

// Serveur central des mises à jour : registre de paquets du projet GitLab (manifest.json + APK). -PupdateUrl=http://10.0.2.2:8099
// pour un essai local, vide pour le couper.
val updateUrl = (findProperty("updateUrl") as String?)
    ?: "https://gitlab.unowhy.dev/api/v4/projects/891/packages/generic/companion-updates/latest"
// En-tête envoyé au seul serveur central, « Nom: valeur ». La CI release passe « Deploy-Token: … » (lecture seule du registre) ;
// vide en debug : le registre répond 401, la tablette n'y voit aucune mise à jour.
val updateHeader = (findProperty("updateHeader") as String?) ?: ""

// Version : celle du tag vX.Y.Z en CI (-PappVersion=X.Y.Z). versionCode = X × 10000 + Y × 100 + Z, toujours croissant.
// Sans tag : 0.0.0, code 1 (Android refuse 0) : une build locale ne bloque que v0.0.1, et un APK debug ne reçoit
// de toute façon pas un APK release (signature différente).
val appVersion = (findProperty("appVersion") as String?) ?: "0.0.0"
val appVersionCode = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})""").matchEntire(appVersion)
    ?.destructured?.let { (x, y, z) -> (x.toInt() * 10000 + y.toInt() * 100 + z.toInt()).coerceAtLeast(1) }
    ?: throw GradleException("appVersion « $appVersion » : attendu X.Y.Z, Y et Z inférieurs à 100")

// Clé de signature release, jamais dans le dépôt : fichier PKCS12 et mot de passe donnés par l'environnement (CI).
val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE").orNull

android {
    namespace = "com.unowhy.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.unowhy.companion"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "TECH_CODE", "\"$techCode\"")
        buildConfigField("String", "INSTALL_URL", "\"$installUrl\"")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        buildConfigField("String", "UPDATE_HEADER", "\"$updateHeader\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
            keyAlias = "companion"
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            // Sans clé (poste de dev) : APK release non signé.
            signingConfig = signingConfigs.findByName("release")
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
    // Ressorts (SpringAnimation) : mouvement de la DA Cadran.
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // Vraie implémentation de org.json : celle d'android.jar n'est qu'un bouchon sur la JVM.
    testImplementation("org.json:json:20250517")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
