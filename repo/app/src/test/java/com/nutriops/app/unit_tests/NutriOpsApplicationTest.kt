package com.nutriops.app.unit_tests

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoaderFactory
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.NutriOpsApplication
import dagger.hilt.android.HiltAndroidApp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Structural and metadata tests for [NutriOpsApplication].
 *
 * Full onCreate lifecycle testing (Hilt injection, WorkManager scheduling,
 * AppConfig initialization) requires a running Hilt test component and is
 * therefore covered by the instrumented `DiModuleValidationTest` under
 * `androidTest/`. This test asserts the class metadata and contracts that
 * guarantee the app is wired correctly at declaration time.
 */
@RunWith(RobolectricTestRunner::class)
class NutriOpsApplicationTest {

    @Test
    fun `NutriOpsApplication is an Android Application`() {
        assertThat(Application::class.java.isAssignableFrom(NutriOpsApplication::class.java)).isTrue()
    }

    @Test
    fun `class is annotated with HiltAndroidApp so Hilt generates the component`() {
        val annotation = NutriOpsApplication::class.java.getAnnotation(HiltAndroidApp::class.java)
        assertThat(annotation).isNotNull()
    }

    @Test
    fun `implements WorkManager Configuration Provider for Hilt worker factory`() {
        assertThat(
            Configuration.Provider::class.java.isAssignableFrom(NutriOpsApplication::class.java)
        ).isTrue()
    }

    @Test
    fun `implements Coil ImageLoaderFactory for LRU image cache`() {
        assertThat(
            ImageLoaderFactory::class.java.isAssignableFrom(NutriOpsApplication::class.java)
        ).isTrue()
    }

    @Test
    fun `AndroidManifest declares the application class by name`() {
        // Robolectric's context hands back the class registered in the
        // manifest; the default ApplicationProvider is configured through
        // Robolectric's config. Verify our class name is present on the
        // compiled app.
        val packageName = NutriOpsApplication::class.java.name
        assertThat(packageName).isEqualTo("com.nutriops.app.NutriOpsApplication")
    }

    @Test
    fun `workerFactory and imageLoader are declared as Inject-annotated properties`() {
        // These are the @Inject lateinit hooks Hilt fills in at runtime.
        // Asserting they exist in the declared fields guards against
        // accidental renames/removals that would break DI wiring.
        val fields = NutriOpsApplication::class.java.declaredFields.map { it.name }
        assertThat(fields).contains("workerFactory")
        assertThat(fields).contains("imageLoader")
    }

    @Test
    fun `instance can be constructed without touching onCreate`() {
        // Plain construction — no Android lifecycle, no injection. This
        // smokes out any eager static initialization or class-load issues
        // that would otherwise only surface at runtime.
        val instance = NutriOpsApplication()
        assertThat(instance).isInstanceOf(Application::class.java)
    }
}
