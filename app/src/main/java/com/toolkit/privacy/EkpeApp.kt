package com.toolkit.privacy

import android.app.Application

/** Loads the saved theme and master-switch state before any screen, service or receiver runs. */
class EkpeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeStore.init(this)
        MasterSwitch.init(this)
    }
}
