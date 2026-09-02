import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Shared configuration for every Android library module: SDK levels, JVM target
 * and the common test stack, so no library module repeats them.
 *
 * Compose wiring is attached only to modules that also apply the Compose compiler
 * plugin, which keeps non-UI modules such as `:core:data` free of Compose.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun version(name: String) = libs.findVersion(name).get().requiredVersion
        fun library(name: String) = libs.findLibrary(name).get()

        extensions.configure<LibraryExtension> {
            compileSdk = version("compileSdk").toInt()

            defaultConfig {
                minSdk = version("minSdk").toInt()
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }

        dependencies {
            "implementation"(library("kotlinx-coroutines-android"))

            "testImplementation"(library("junit"))
            "testImplementation"(library("kotlinx-coroutines-test"))
            "testImplementation"(library("turbine"))
            "testImplementation"(platform(library("okhttp-bom")))
            "testImplementation"(library("okhttp-mockwebserver"))

            "androidTestImplementation"(library("androidx-junit"))
            "androidTestImplementation"(library("androidx-espresso-core"))
            "androidTestImplementation"(library("kotlinx-coroutines-test"))
            "androidTestImplementation"(library("turbine"))
        }

        pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
            extensions.configure<LibraryExtension> {
                buildFeatures {
                    compose = true
                }
            }
            dependencies {
                val composeBom = platform(library("androidx-compose-bom"))
                "implementation"(composeBom)
                "implementation"(library("androidx-compose-ui"))
                "implementation"(library("androidx-compose-ui-graphics"))
                "implementation"(library("androidx-compose-ui-tooling-preview"))
                "implementation"(library("androidx-compose-material3"))

                "androidTestImplementation"(composeBom)
                "androidTestImplementation"(library("androidx-compose-ui-test-junit4"))

                "debugImplementation"(library("androidx-compose-ui-tooling"))
                "debugImplementation"(library("androidx-compose-ui-test-manifest"))
            }
        }
    }
}
