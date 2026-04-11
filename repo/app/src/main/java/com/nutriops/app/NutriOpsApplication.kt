package com.nutriops.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.nutriops.app.config.AppConfig
import com.nutriops.app.logging.AppLogger
import com.nutriops.app.worker.ReminderWorker
import com.nutriops.app.worker.RuleEvaluationWorker
import com.nutriops.app.worker.SlaCheckWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NutriOpsApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: ImageLoader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun newImageLoader(): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        AppConfig.initialize(this)
        AppLogger.info("App", "NutriOps application started")
        schedulePeriodicWork()
    }

    private fun schedulePeriodicWork() {
        val workManager = WorkManager.getInstance(this)

        val deferredConstraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        // Schedule reminder delivery - runs every 15 minutes when device is idle
        val reminderWork = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES)
            .setConstraints(deferredConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ReminderWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            reminderWork
        )

        // Schedule SLA checks - runs every 30 minutes when device is idle
        val slaWork = PeriodicWorkRequestBuilder<SlaCheckWorker>(30, TimeUnit.MINUTES)
            .setConstraints(deferredConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SlaCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            slaWork
        )

        // Schedule rule evaluation - runs every 30 minutes when device is idle
        val ruleEvalWork = PeriodicWorkRequestBuilder<RuleEvaluationWorker>(30, TimeUnit.MINUTES)
            .setConstraints(deferredConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            RuleEvaluationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            ruleEvalWork
        )

        AppLogger.info("App", "Periodic workers scheduled (reminders: 15min, SLA: 30min, rules: 30min)")
    }
}
