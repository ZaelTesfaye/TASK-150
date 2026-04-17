package com.nutriops.app.di

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom JUnit runner that swaps the production [com.nutriops.app.NutriOpsApplication]
 * for Hilt's [HiltTestApplication] so tests annotated with `@HiltAndroidTest`
 * can spin up a real Hilt component without the production startup side
 * effects (worker scheduling, periodic tasks, etc.).
 *
 * Wired in via the `testInstrumentationRunner` property in app/build.gradle.kts.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
