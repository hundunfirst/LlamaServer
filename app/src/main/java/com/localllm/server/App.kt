package com.localllm.server

import android.app.Application
import com.localllm.server.core.AppSettings
import com.localllm.server.core.BinaryInstaller
import com.localllm.server.core.ModelRepository

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        BinaryInstaller.init(this)
        modelRepository = ModelRepository(this)
    }

    companion object {
        lateinit var modelRepository: ModelRepository
            private set
    }
}
