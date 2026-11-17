package com.alma.toolbox

import android.app.Application
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Stores.init(this)
        Notify.createBaseChannels(this)
    }
}
