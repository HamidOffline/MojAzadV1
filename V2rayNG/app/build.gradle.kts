plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

/*
 * MojAzad Release signing.
 *
 * Release builds MUST use the permanent MojAzad signing key.
 * Debug builds continue working without these environment variables.
 */
val isReleaseBuildRequested =
    gradle.startParameter.taskNames.any {
        it.contains(
            "Release",
            ignoreCase = true
        )
    }

android {
    // Keep original source namespace.
    namespace = "com.v2ray.ang"

    compileSdk = 37

    defaultConfig {
        applicationId = "com.mojazad.vpn"

        minSdk = 24
        targetSdk = 37

        /*
         * MojAzad 3.0.1 Beta 1
         *
         * Development branch:
         * MojAzadV4
         *
         * Future stable version:
         * 3.0.1
         */
        versionCode = 741
        versionName = "3.0.1-beta1"

        multiDexEnabled = true

        val abiFilterList =
            (properties["ABI_FILTERS"] as? String)
                ?.split(';')

        splits {
            abi {
                isEnable = true

                reset()

                if (
                    !abiFilterList.isNullOrEmpty()
                ) {
                    include(
                        *abiFilterList.toTypedArray()
                    )
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }

                /*
                 * Full release builds also create:
                 * universal APK.
                 */
                isUniversalApk =
                    abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    /*
     * Permanent MojAzad Release signing.
     *
     * Values are supplied by GitHub Actions secrets.
     */
    signingConfigs {
        create("release") {

            if (isReleaseBuildRequested) {

                val keystorePath =
                    System.getenv(
                        "MOJAZAD_KEYSTORE_PATH"
                    )
                        ?: error(
                            "MOJAZAD_KEYSTORE_PATH is missing"
                        )

                val keystorePassword =
                    System.getenv(
                        "MOJAZAD_KEYSTORE_PASSWORD"
                    )
                        ?: error(
                            "MOJAZAD_KEYSTORE_PASSWORD is missing"
                        )

                val alias =
                    System.getenv(
                        "MOJAZAD_KEY_ALIAS"
                    )
                        ?: error(
                            "MOJAZAD_KEY_ALIAS is missing"
                        )

                val aliasPassword =
                    System.getenv(
                        "MOJAZAD_KEY_PASSWORD"
                    )
                        ?: error(
                            "MOJAZAD_KEY_PASSWORD is missing"
                        )

                storeFile =
                    file(
                        keystorePath
                    )

                storePassword =
                    keystorePassword

                keyAlias =
                    alias

                keyPassword =
                    aliasPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false

            signingConfig =
                signingConfigs
                    .getByName(
                        "release"
                    )

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add(
        "distribution"
    )

    productFlavors {
        create("fdroid") {
            dimension =
                "distribution"

            applicationIdSuffix =
                ".fdroid"

            buildConfigField(
                "String",
                "DISTRIBUTION",
                "\"F-Droid\""
            )
        }

        create("playstore") {
            dimension =
                "distribution"

            buildConfigField(
                "String",
                "DISTRIBUTION",
                "\"Play Store\""
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs(
                "libs"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled =
            true

        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl
                    .JvmTarget
                    .JVM_17
            )
        }
    }

    applicationVariants.all {

        val variant =
            this

        val isFdroid =
            variant.productFlavors.any {
                it.name == "fdroid"
            }

        if (isFdroid) {

            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2,
                    "arm64-v8a" to 1,
                    "x86" to 4,
                    "x86_64" to 3,
                    "universal" to 0
                )

            variant.outputs
                .map {
                    it as com.android.build.gradle.internal.api.ApkVariantOutputImpl
                }
                .forEach { output ->

                    val abi =
                        output.getFilter(
                            "ABI"
                        )
                            ?: "universal"

                    output.outputFileName =
                        "MojAzad_${variant.versionName}-fdroid_${abi}.apk"

                    if (
                        versionCodes.containsKey(
                            abi
                        )
                    ) {

                        output.versionCodeOverride =
                            (
                                100 *
                                    variant.versionCode +
                                    versionCodes[abi]!!
                                )
                                .plus(
                                    5000000
                                )

                    } else {

                        return@forEach
                    }
                }

        } else {

            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 4,
                    "arm64-v8a" to 4,
                    "x86" to 4,
                    "x86_64" to 4,
                    "universal" to 4
                )

            variant.outputs
                .map {
                    it as com.android.build.gradle.internal.api.ApkVariantOutputImpl
                }
                .forEach { output ->

                    val abi =
                        output.getFilter(
                            "ABI"
                        )
                            ?: "universal"

                    output.outputFileName =
                        "MojAzad_${variant.versionName}_${abi}.apk"

                    if (
                        versionCodes.containsKey(
                            abi
                        )
                    ) {

                        output.versionCodeOverride =
                            (
                                1000000 *
                                    versionCodes[abi]!!
                                )
                                .plus(
                                    variant.versionCode
                                )

                    } else {

                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        viewBinding =
            true

        buildConfig =
            true
    }

    packaging {
        jniLibs {
            useLegacyPackaging =
                true
        }
    }
}

dependencies {

    implementation(
        fileTree(
            mapOf(
                "dir" to "libs",
                "include" to listOf(
                    "*.aar",
                    "*.jar"
                )
            )
        )
    )

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.appcompat
    )

    implementation(
        libs.androidx.activity
    )

    implementation(
        libs.androidx.constraintlayout
    )

    implementation(
        libs.preference.ktx
    )

    implementation(
        libs.recyclerview
    )

    implementation(
        libs.androidx.swiperefreshlayout
    )

    implementation(
        libs.androidx.viewpager2
    )

    implementation(
        libs.androidx.fragment
    )

    implementation(
        libs.material
    )

    implementation(
        libs.toasty
    )

    implementation(
        libs.editorkit
    )

    implementation(
        libs.flexbox
    )

    implementation(
        libs.mmkv.static
    )

    implementation(
        libs.gson
    )

    implementation(
        libs.okhttp
    )

    implementation(
        libs.kotlinx.coroutines.android
    )

    implementation(
        libs.kotlinx.coroutines.core
    )

    implementation(
        libs.language.base
    )

    implementation(
        libs.language.json
    )

    implementation(
        libs.quickie.foss
    )

    implementation(
        libs.core
    )

    implementation(
        libs.lifecycle.viewmodel.ktx
    )

    implementation(
        libs.lifecycle.livedata.ktx
    )

    implementation(
        libs.lifecycle.runtime.ktx
    )

    implementation(
        libs.work.runtime.ktx
    )

    implementation(
        libs.work.multiprocess
    )

    implementation(
        libs.multidex
    )

    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    testImplementation(
        libs.org.mockito.mockito.inline
    )

    testImplementation(
        libs.mockito.kotlin
    )

    coreLibraryDesugaring(
        libs.desugar.jdk.libs
    )
}
