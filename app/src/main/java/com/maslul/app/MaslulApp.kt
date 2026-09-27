package com.maslul.app

import android.app.Application
import com.maslul.app.data.LocationRepo
import com.maslul.app.data.TransitRepository
import com.maslul.app.data.UserStore
import org.maplibre.android.MapLibre

class MaslulApp : Application() {
    lateinit var repo: TransitRepository
        private set
    lateinit var store: UserStore
        private set
    lateinit var location: LocationRepo
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        MapLibre.getInstance(this)
        repo = TransitRepository()
        store = UserStore(this)
        location = LocationRepo(this)
    }

    companion object {
        lateinit var instance: MaslulApp
            private set
    }
}
