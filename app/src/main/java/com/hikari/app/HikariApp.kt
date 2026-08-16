package com.hikari.app

import android.app.Application
import com.hikari.app.data.AppStore
import com.hikari.app.net.Http
import com.hikari.app.providers.ProviderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HikariApp : Application() {

    companion object {
        lateinit var instance: HikariApp
            private set
    }

    lateinit var store: AppStore
        private set
    lateinit var providers: ProviderManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = AppStore(this)
        providers = ProviderManager(store)
        Http.init()
        CoroutineScope(Dispatchers.IO).launch { providers.refresh() }
    }
}
