package com.hhst.youtubelite

import android.app.Application
import com.hhst.youtubelite.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Process-level entry point for the app.
 *
 * Starts Koin so dependencies are available before any [android.app.Activity]
 * is created. Keep initialization here limited to work that must run once per
 * process; prefer lazy setup inside feature modules when possible.
 */
class LiteApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@LiteApplication)
            modules(appModule)
        }
    }
}
