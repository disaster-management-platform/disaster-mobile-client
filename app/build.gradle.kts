plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dagger)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.mapsplatform.secrets.plugin)
}

val apiBaseUrl = providers.gradleProperty("API_BASE_URL")
    .orElse("http://10.0.2.2:5080/api/").get()
require(apiBaseUrl.endsWith("/api/") && (apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://"))) {
    "API_BASE_URL debe ser HTTP(S) y terminar en /api/"
}

android {
    namespace = "co.edu.javeriana.disasterplatform.disaster_mobile_client"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "co.edu.javeriana.disasterplatform.disaster_mobile_client"
        minSdk = 25
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // El plugin de secrets reemplaza este valor para la app; las pruebas no necesitan Maps.
        manifestPlaceholders["MAPS_API_KEY"] = ""
        manifestPlaceholders["NETWORK_SECURITY_CONFIG"] = "@xml/network_security_config"
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

tasks.matching { it.name == "generateReleaseBuildConfig" }.configureEach {
    val releaseUrl = apiBaseUrl
    doFirst {
        require(releaseUrl.startsWith("https://")) { "API_BASE_URL de release debe utilizar HTTPS" }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.dagger.hilt)
    implementation(libs.hilt.compose.navigation)
    ksp(libs.dagger.compiler)

    // Coil
    implementation(libs.coil.compose)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.converter.scalars)

    // Google Maps & Location
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.android.maps.utils)
}

// Optional public CA for a private HTTPS demonstration deployment. Never a key or a token.
val deploymentCa = providers.gradleProperty("DEPLOYMENT_CA_FILE")
if (deploymentCa.isPresent) {
    val generatedTrust = layout.buildDirectory.dir("generated/deploymentTrust").get().asFile
    val publicCa = file(deploymentCa.get())
    val generateDeploymentTrust by tasks.registering {
        inputs.file(publicCa)
        outputs.dir(generatedTrust)
        doLast {
            val directory = generatedTrust
            directory.resolve("raw").mkdirs()
            publicCa.copyTo(directory.resolve("raw/deployment_ca.crt"), overwrite = true)
            directory.resolve("xml").mkdirs()
            directory.resolve("xml/deployment_network_security_config.xml").writeText("""
                <network-security-config>
                  <base-config cleartextTrafficPermitted="false">
                    <trust-anchors><certificates src="system"/><certificates src="@raw/deployment_ca"/></trust-anchors>
                  </base-config>
                </network-security-config>
            """.trimIndent())
        }
    }
    android.sourceSets.getByName("debug").res.srcDir(generatedTrust)
    android.buildTypes.getByName("debug").manifestPlaceholders["NETWORK_SECURITY_CONFIG"] = "@xml/deployment_network_security_config"
    tasks.named("preBuild").configure { dependsOn(generateDeploymentTrust) }
}
