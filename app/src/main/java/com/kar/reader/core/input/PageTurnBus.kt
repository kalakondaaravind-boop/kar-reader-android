package com.kar.reader.core.input

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class PageTurn { NEXT, PREVIOUS }

/**
 * Routes page-turn keys (volume keys, keyboard arrows, Page Up/Down, Space, and Bluetooth page-turner
 * remotes, which send these same key codes) to whichever reader is on screen.
 */
@Singleton
class PageTurnBus @Inject constructor() {
    private val _turns = MutableSharedFlow<PageTurn>(extraBufferCapacity = 8)
    val turns: SharedFlow<PageTurn> = _turns.asSharedFlow()

    private val activeReaders = AtomicInteger(0)

    fun readerShown() { activeReaders.incrementAndGet() }
    fun readerHidden() { activeReaders.decrementAndGet() }

    /** Returns true when the key was consumed as a page turn. Volume keys are used only when enabled and a reader is open. */
    fun handle(event: KeyEvent, volumeKeysEnabled: Boolean): Boolean {
        if (activeReaders.get() <= 0) return false
        val turn = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeysEnabled) PageTurn.NEXT else null
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeysEnabled) PageTurn.PREVIOUS else null
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PageTurn.NEXT
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> PageTurn.PREVIOUS
            else -> null
        } ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) _turns.tryEmit(turn)
        return true
    }
}
