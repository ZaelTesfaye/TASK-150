package com.nutriops.app.data.local

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.nutriops.app.logging.AppLogger
import net.sqlcipher.database.SupportFactory

object DatabaseFactory {

    fun create(context: Context, encryptionKey: String): NutriOpsDatabase {
        val passphrase = encryptionKey.toByteArray()
        val factory = SupportFactory(passphrase)

        val driver = AndroidSqliteDriver(
            schema = NutriOpsDatabase.Schema,
            context = context,
            name = "nutriops.db",
            factory = factory
        )

        AppLogger.info("Database", "Encrypted database initialized successfully")

        return NutriOpsDatabase(driver)
    }

}
