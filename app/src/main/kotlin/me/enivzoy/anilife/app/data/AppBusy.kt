package me.enivzoy.anilife.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Work that must finish before the app is used again: a list sync, a backup restore, an import.
 * While anything is running inside [hold], the whole window ignores the mouse and the keyboard
 * (see the overlay in App.kt). A count rather than a flag, so two holds that overlap are both
 * waited for.
 */
object AppBusy {
    private val mutableHolds = MutableStateFlow(0)

    /** How many holds are running now; the window is blocked while it is above zero. */
    val holds: StateFlow<Int> = mutableHolds.asStateFlow()
    val isActive: Boolean get() = mutableHolds.value > 0

    suspend fun <T> hold(work: suspend () -> T): T {
        mutableHolds.update { it + 1 }
        try {
            return work()
        } finally {
            mutableHolds.update { it - 1 }
        }
    }
}
