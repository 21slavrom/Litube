package com.hhst.youtubelite.di

import com.hhst.youtubelite.ui.main.MainViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Root Koin module for application-scoped bindings.
 *
 * Feature modules should be defined separately and composed into
 * [org.koin.core.context.startKoin] as each feature is rebuilt.
 */
val appModule = module {
    viewModelOf(::MainViewModel)
}
