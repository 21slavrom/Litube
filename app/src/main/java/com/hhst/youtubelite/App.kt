package com.hhst.youtubelite

import android.app.Application
import com.hhst.youtubelite.di.appModule
import com.hhst.youtubelite.extractor.PoTokenProvider
import com.tencent.mmkv.MMKV
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/** Process entry: MMKV, Koin, and optional poToken WebView init. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@App)
            modules(appModule)
        }
        runCatching { get<PoTokenProvider>().initialize() }
    }
}
