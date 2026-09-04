plugins {
    alias(libs.plugins.fetchclone.android.library)
}

android {
    namespace = "com.fetchclone.core.testing"
}

dependencies {
    // `api`, not `implementation`, and deliberately so. This module's whole purpose is to
    // hand test infrastructure to other modules' test source sets -- a consumer that gets
    // `MainDispatcherRule` but not the JUnit `TestWatcher` it extends, or not the
    // `TestDispatcher` in its constructor, cannot compile against it.
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
}
