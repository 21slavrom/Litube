package com.hhst.youtubelite.di

import com.google.gson.Gson
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.core.MmkvJsonCache
import com.hhst.youtubelite.extractor.Cache
import com.hhst.youtubelite.extractor.ClientOrderStore
import com.hhst.youtubelite.extractor.DiskCache
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.HttpDownloader
import com.hhst.youtubelite.extractor.PoTokenProvider
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import com.tencent.mmkv.MMKV
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/** Application-scoped Koin module. */
val appModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    single { Gson() }
    single { MMKV.defaultMMKV() }
    single<JsonCache> { MmkvJsonCache(kv = get(), gson = get()) }
    single<Cache> { DiskCache(store = get()) }
    single { ClientOrderStore(kv = get()) }

    single { HttpDownloader(get()) }
    single { PoTokenProvider(androidContext(), get()) }
    single {
        Extractor(
            downloader = get(),
            cache = get(),
            poToken = get<PoTokenProvider>(),
            clientOrder = get(),
        )
    }
    viewModelOf(::BrowserViewModel)
}
