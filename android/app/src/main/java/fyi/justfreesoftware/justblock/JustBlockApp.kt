package fyi.justfreesoftware.justblock

import android.app.Application

class JustBlockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}