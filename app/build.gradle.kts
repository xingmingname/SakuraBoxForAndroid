@file:Suppress("UnstableApiUsage")

import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

android {
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
    defaultConfig {
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {

    // Framework test APIs are optional SDK libraries; keep them out of APKs.
    androidTestCompileOnly(files("${android.sdkDirectory}/platforms/android-35/optional/android.test.base.jar"))
    androidTestCompileOnly(files("${android.sdkDirectory}/platforms/android-35/optional/android.test.runner.jar"))

    val localCoreAar = providers.gradleProperty("nekoCoreAar").orNull
        ?: file("libs/libcore-sing-box-1.14.3-sakura-sr01.aar").absolutePath
    require(file(localCoreAar).isFile) {
        "Build the current SakuraBox libcore AAR first, or set -PnekoCoreAar to its verified path."
    }
    implementation(fileTree("libs") { exclude("libcore*.aar") })
    implementation(files(localCoreAar))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.8.0")
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

    testImplementation("junit:junit:4.13.2")
}

// Instrumentation executes the application's classes. Resolve their shared
// dependencies to the exact production versions rather than a separate graph.
configurations.matching {
    it.name.endsWith("AndroidTestCompileClasspath") || it.name.endsWith("AndroidTestRuntimeClasspath")
}.configureEach {
    val productionPrefix = name.removeSuffix("AndroidTestCompileClasspath")
        .removeSuffix("AndroidTestRuntimeClasspath")
    var productionVersions: Map<String, String>? = null
    resolutionStrategy.eachDependency {
        if (productionVersions == null) {
            val versions = mutableMapOf<String, String>()
            for (suffix in listOf("CompileClasspath", "RuntimeClasspath")) {
                configurations.getByName(productionPrefix + suffix).incoming.resolutionResult.allComponents
                    .mapNotNull { it.id as? ModuleComponentIdentifier }
                    .forEach {
                        versions["${it.group}:${it.module}"] = it.version
                        if (it.module.endsWith("-jvm")) versions["${it.group}:${it.module.removeSuffix("-jvm")}"] = it.version
                    }
            }
            productionVersions = versions
        }
        productionVersions?.get("${requested.group}:${requested.name}")?.let { useVersion(it) }
    }
}
