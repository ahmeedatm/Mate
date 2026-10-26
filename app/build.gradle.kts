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

// Version : celle du tag vX.Y.Z en CI (-PappVersion=X.Y.Z). versionCode = X × 10000 + Y × 100 + Z, toujours croissant.
val appVersion = (findProperty("appVersion") as String?) ?: "0.1.0"
val appVersionCode = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})""").matchEntire(appVersion)
    ?.destructured?.let { (x, y, z) -> x.toInt() * 10000 + y.toInt() * 100 + z.toInt() }
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

    testOptions {
        unitTests.isReturnDefaultValues = true
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
}
