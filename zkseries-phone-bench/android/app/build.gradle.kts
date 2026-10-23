plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.zkseries.bench"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.zkseries.bench"
        // Nothing in the sources needs a library API above Java 11; 31 is kept from
        // the earlier build and is below the A71's Android 13 (API 33).
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // The sources are consumed in place, not copied, so the handset and the desktop
    // figures come from one set of files:
    //   SharpRangeProofs-Code/src  the ZK-Series tree: Ristretto255, the Bulletproofs
    //                              baseline (experiments.BulletproofsBench)
    //   bppp-java-zkseries/src     Bulletproofs++ and the authentication proof
    //                              (bppp.AuthBench); it depends on the tree above
    sourceSets["main"].java.srcDirs(
        "../../SharpRangeProofs-Code/src",
        "../../bppp-java-zkseries/src",
        "../../bppp-java-unoptimized/src"
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        // curve25519-elisabeth is a modular jar; D8 has no use for the descriptor.
        resources.excludes += "META-INF/versions/9/module-info.class"
        resources.excludes += "META-INF/*.kotlin_module"
    }

    buildTypes {
        release {
            // Left off deliberately: R8 would shrink and inline across the code under
            // measurement, which the desktop figures do not have.
            isMinifyEnabled = false
            isDebuggable = false
            // Signed with the debug key so that installRelease exists; the build is
            // still non-debuggable, which is what matters for the measurements.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Not debuggable either: a debuggable process runs with the JIT held back and
            // reads several times slower, and this app exists only to measure. With this,
            // pressing Run in Android Studio gives valid figures whichever variant is selected.
            isMinifyEnabled = false
            isDebuggable = false
        }
    }
}

dependencies {
    implementation(files("../../SharpRangeProofs-Code/lib/curve25519-elisabeth-0.1.5.jar"))
    implementation(files("../../SharpRangeProofs-Code/lib/keccakj-1.1.0.jar"))
}
