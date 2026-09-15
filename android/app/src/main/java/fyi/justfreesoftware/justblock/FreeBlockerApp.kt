package fyi.justfreesoftware.justblock

import android.app.Application

class FreeBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}