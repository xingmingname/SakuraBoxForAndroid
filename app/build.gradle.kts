@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

val projectLicenseAssets = layout.buildDirectory.dir("generated/assets/projectLicense")
val generateProjectLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE")) { into("project-license") }
    from(rootProject.file("libcore/nakis/LICENSE")) {
        into("project-license")
        rename { "NAKIS-LICENSE" }
    }
    into(projectLicenseAssets)
}

android {
    sourceSets.getByName("main").assets.srcDir(projectLicenseAssets)
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

tasks.named("preBuild") { dependsOn(generateProjectLicenseAssets) }

dependencies {

    val localCoreAar = providers.gradleProperty("nekoCoreAar").orNull
        ?: file("libs/libcore-sing-box-1.14.3-sakura-with_nakis.aar").absolutePath
    require(file(localCoreAar).isFile) {
        "Build the SakuraBox libcore AAR with Nakis and Sudoku first, or set -PnekoCoreAar to its verified path. See BUILDING.md."
    }
    implementation(fileTree("libs") { exclude("libcore*.aar") })
    implementation(files(localCoreAar))

    // Keep the tested dependency version; upgrades require separate compatibility validation.
    //noinspection GradleDependency
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    // Keep the tested dependency version; upgrades require separate compatibility validation.
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.9.0")
    // Reuse the ExifInterface version already resolved through CameraX.
    //noinspection GradleDependency
    implementation("androidx.exifinterface:exifinterface:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Keep the tested dependency version; upgrades require separate compatibility validation.
    //noinspection GradleDependency
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    // Keep the tested dependency version; upgrades require separate compatibility validation.
    //noinspection GradleDependency
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    // Keep the tested dependency version; upgrades require separate compatibility validation.
    //noinspection GradleDependency
    implementation("com.google.android.material:material:1.8.0")
    // Keep the tested dependency version; upgrades require separate compatibility validation.
    //noinspection GradleDependency
    implementation("com.google.code.gson:gson:2.9.0")

    implementation("com.github.jenly1314:zxing-lite:2.1.1")
    implementation("com.blacksquircle.ui:editorkit:2.6.0")
    implementation("com.blacksquircle.ui:language-base:2.6.0")
    implementation("com.blacksquircle.ui:language-json:2.6.0")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("org.yaml:snakeyaml:1.30")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")
    implementation("com.esotericsoftware:kryo:5.2.1")
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("org.ini4j:ini4j:0.5.4")

    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4")
    ksp("com.github.MatrixDev.Roomigrant:RoomigrantCompiler:0.3.4")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
