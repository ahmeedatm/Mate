plugins {
    id("com.android.application")
}

// URL du backend par défaut. Avec `adb reverse tcp:3000 tcp:3000`, la tablette
// joint le backend du Mac via le câble USB, sans dépendre du WiFi.
// Surchargeable : ./gradlew installDebug -PbackendUrl=http://192.168.1.42:3000
val backendUrl = (findProperty("backendUrl") as String?) ?: "http://127.0.0.1:3000"

// Code technicien (appui long sur le titre de Réglages) : montre la carte Kiosque. -PtechCode=… pour le changer.
val techCode = (findProperty("techCode") as String?) ?: "4242"

// Mises à jour : assets des releases GitHub du dépôt `updateRepo` (tag `latest` : stables, tag `essais` : essais de PR),
// lus par l'API GitHub avec `updateToken`, jeton en lecture seule du dépôt (privé). Le jeton est dans l'APK, jamais dans
// le dépôt : la CI release le passe (-PupdateToken=…), vide en debug (aucune mise à jour vue). -PupdateRepo= (vide) coupe la source.
val updateRepo = (findProperty("updateRepo") as String?) ?: "ahmeedatm/Mate"
val updateToken = (findProperty("updateToken") as String?) ?: ""
// Serveur HTTP simple à la place de GitHub, pour un essai local : -PupdateUrl=http://10.0.2.2:8099 (essais sous /essais).
val updateUrl = (findProperty("updateUrl") as String?) ?: ""

// Version : celle du tag vX.Y.Z en CI (-PappVersion=X.Y.Z). versionCode = (X × 10000 + Y × 100 + Z) × 1000, toujours croissant.
// Essai depuis une PR : -PappVersion=X.Y.Z-essai.N, X.Y.Z = dernier tag de la branche, N = numéro
// d'exécution de la CI ; code = celui du tag + 1 + N mod 999 : au-dessus du tag, sous le suivant.
// Sans tag : 0.0.0, code 1 (Android refuse 0) : une build locale ne bloque aucun tag, et un APK debug ne reçoit
// de toute façon pas un APK release (signature différente).
val appVersion = (findProperty("appVersion") as String?) ?: "0.0.0"
val versionParts = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})(?:-essai\.(\d+))?""").matchEntire(appVersion)?.destructured
    ?: throw GradleException("appVersion « $appVersion » : attendu X.Y.Z ou X.Y.Z-essai.N, Y et Z inférieurs à 100")
val appVersionCode = versionParts.let { (x, y, z, n) ->
    val tag = (x.toInt() * 10000 + y.toInt() * 100 + z.toInt()) * 1000
    (if (n.isEmpty()) tag else tag + 1 + n.toInt() % 999).coerceAtLeast(1)
}
// Un essai est une autre appli (app.mate.essai, « Mate essai »), installée à côté de la stable : la
// stable reste device owner et kiosque, et on revient à elle sans réinstaller (docs/developpement.md, section Release).
val trial = versionParts.component4().isNotEmpty()

// Clé de signature release, jamais dans le dépôt : fichier PKCS12 et mot de passe donnés par l'environnement (CI).
val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE").orNull

android {
    namespace = "app.mate"
    compileSdk = 36

    defaultConfig {
        // Identité Android d'avant le renommage Mate, à ne jamais changer : les tablettes en kiosque ne se mettent à
        // jour que vers leur propre paquet (device owner, AutoUpdater). Le code, lui, est dans app.mate.
        applicationId = "app.mate"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
        if (trial) applicationIdSuffix = ".essai"
        manifestPlaceholders["appLabel"] = if (trial) "Mate essai" else "Mate"
        buildConfigField("boolean", "TRIAL", "$trial")
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "TECH_CODE", "\"$techCode\"")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
        // Bibliothèques natives de Vosk (10 Mo par ABI) : la tablette (arm64-v8a), les lots 32 bits (armeabi-v7a) et
        // l'émulateur (x86_64) ; x86, armeabi et mips n'ont pas de Vosk de toute façon.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Bibliothèques natives compressées dans l'APK (décompressées à l'installation) : APK de 17 Mo au lieu de 48,
    // c'est lui que les mises à jour téléchargent.
    packaging {
        jniLibs.useLegacyPackaging = true
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Tests JVM : android.util.Log et consorts renvoient des valeurs par défaut au lieu de lever une exception.
    // Erreurs de lint déjà présentes figées dans lint-baseline.xml : seules les nouvelles ressortent (`./gradlew lintDebug`, hors CI).
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
    // Ressorts (SpringAnimation) : mouvement de la DA Cadran.
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    // Mot de réveil « Mate » : reconnaissance hors ligne sur la tablette (Apache 2.0, sans clé ni compte). JNA en aar,
    // comme la démo Android de Vosk : ses bibliothèques natives par ABI, à la version que demande vosk-android.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
    // Vraie implémentation de org.json : celle d'android.jar n'est qu'un bouchon sur la JVM.
    testImplementation("org.json:json:20250517")
}
