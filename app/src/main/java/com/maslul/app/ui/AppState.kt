package com.maslul.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import com.maslul.app.MaslulApp
import com.maslul.app.ui.lines.LinesHomeModel
import com.maslul.app.ui.lines.StationsHomeModel
import com.maslul.app.ui.nav.NavHomeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

enum class Tab { NAVIGATE, LINES, STATIONS }

/** State holder for one screen on a back stack; lives until the screen is popped. */
abstract class ScreenModel(val nav: AppNav) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val app: MaslulApp get() = MaslulApp.instance
    val repo get() = app.repo
    val store get() = app.store
    val location get() = app.location
    open fun dispose() = scope.cancel()
}

class AppNav : ViewModel() {
    var tab by mutableStateOf(Tab.NAVIGATE)
    private val stacks: Map<Tab, SnapshotStateList<ScreenModel>> = mapOf(
        Tab.NAVIGATE to mutableStateListOf(NavHomeModel(this)),
        Tab.LINES to mutableStateListOf(LinesHomeModel(this)),
        Tab.STATIONS to mutableStateListOf(StationsHomeModel(this)),
    )

    /** Incremented on navigation so transitions know the direction. */
    var depthChange by mutableStateOf(0)
        private set

    val stack: List<ScreenModel> get() = stacks.getValue(tab)
    val current: ScreenModel get() = stack.last()
    val atRoot get() = stack.size == 1

    fun push(s: ScreenModel) {
        depthChange = 1
        stacks.getValue(tab).add(s)
    }

    /** Replaces the top screen (e.g. search → results). */
    fun replace(s: ScreenModel) {
        depthChange = 1
        val st = stacks.getValue(tab)
        st.removeAt(st.lastIndex).dispose()
        st.add(s)
    }

    fun pop(): Boolean {
        val st = stacks.getValue(tab)
        if (st.size <= 1) return false
        depthChange = -1
        st.removeAt(st.lastIndex).dispose()
        return true
    }

    fun popTo(model: ScreenModel) {
        val st = stacks.getValue(tab)
        while (st.size > 1 && st.last() !== model) st.removeAt(st.lastIndex).dispose()
    }

    fun selectTab(t: Tab) {
        if (t == tab) {
            // Re-selecting a tab returns to its root.
            val st = stacks.getValue(t)
            while (st.size > 1) st.removeAt(st.lastIndex).dispose()
        }
        depthChange = 0
        tab = t
    }

    override fun onCleared() {
        stacks.values.flatten().forEach { it.dispose() }
    }
}
