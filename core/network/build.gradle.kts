plugins {
    alias(libs.plugins.fetchclone.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fetchclone.core.network"

    buildFeatures {
        // AuthConfig reads DEBUG_TOKEN_LIFETIME from BuildConfig so the access-token
        // lifetime can differ between debug and release. See AuthConfig for why a
        // 60-second token in debug is worth having.
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("int", "ACCESS_TOKEN_MINUTES", "60")
    }

    buildTypes {
        debug {
            // A 60-second access token. Every screen in the app then crosses an expiry
            // within a minute of use, so the refresh path is exercised constantly on
            // device instead of being a code path nobody has ever watched run.
            // DummyJSON honours `expiresInMins` exactly; 1 is the smallest useful value.
            buildConfigField("int", "ACCESS_TOKEN_MINUTES", "1")
        }
    }
}

dependencies {
    // Retrofit is `api` for exactly one reason: :core:data declares its own service
    // interfaces (ProductsApi, CartsApi) and needs Retrofit's annotations plus the
    // `Retrofit` type this module provides in order to create them.
    api(libs.retrofit.core)

    // The Json instance is shared with :core:data's DTOs, so its configuration
    // (ignoreUnknownKeys, explicitNulls) has to be visible to the modules relying on it.
    api(libs.kotlinx.serialization.json)

    // OkHttp is `implementation` and stays that way. This module is the only place in
    // the codebase where an OkHttpClient, an Interceptor or an Authenticator appears on
    // a compile classpath -- which is what makes "no feature module may touch a token"
    // a compile error rather than a code-review convention.
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit.converter.kotlinx.serialization)

    // DataStore holds the encrypted refresh token. Not Room: this is one small blob of
    // key/value state with no relational shape, and putting a credential in the same
    // database as cached product data would tie a session's lifetime to schema
    // migrations -- a v5 migration bug should not be able to sign every user out.
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // MockWebServer arrives from the convention plugin. The authenticator's concurrency
    // test drives a real OkHttpClient against a real socket -- a fake Interceptor chain
    // cannot reproduce five threads calling `authenticate` at once, which is the only
    // thing that test is trying to prove.
    testImplementation(platform(libs.okhttp.bom))
}
