package com.maslul.app

import android.app.Application
import com.maslul.app.data.LocationRepo
import com.maslul.app.data.TransitRepository
import com.maslul.app.data.UserStore
import com.maslul.app.data.languageOrDefault
import com.maslul.app.i18n.L10n
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
        applyLanguage()
        // Picking another language (or mode) switches the words everywhere, notifications included.
        MainScope().launch {
            store.data.map { it.settings.language to it.settings.simpleMode }.distinctUntilChanged().collect { applyLanguage() }
        }
    }

    /** Speaks the language picked in Settings, else the phone's (which can change while the app runs). */
    fun applyLanguage() {
        val settings = store.data.value.settings
        val language = settings.languageOrDefault()
        L10n.use(language)
        // Simple Maslul asks for place names in its language. The full app doesn't: asking in English
        // ranks the geocoder's matches worse (and Hebrew or Russian changes nothing).
        repo.language = if (settings.simpleMode) language.code else null
    }

    companion object {
        lateinit var instance: MaslulApp
            private set
    }
}
