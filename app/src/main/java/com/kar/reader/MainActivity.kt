package com.kar.reader

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kar.reader.core.input.PageTurnBus
import com.kar.reader.core.lock.AppLockManager
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.prefs.AppSettings
import com.kar.reader.ui.AppRoot
import com.kar.reader.ui.DeepLinkRequest
import com.kar.reader.widget.DeepLinks
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single activity. FragmentActivity is required by BiometricPrompt (app lock).
 * Handles page-turn keys, app-lock timing, screenshots policy and widget deep links.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var pageTurns: PageTurnBus
    @Inject lateinit var lock: AppLockManager
    @Inject lateinit var prefs: AppPreferences

    private val deepLink = MutableStateFlow<DeepLinkRequest?>(null)
    @Volatile private var settings = AppSettings()
    private var firstStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                prefs.settings.collect { s ->
                    settings = s
                    // With app lock on, hide content from the recent-apps preview and block screenshots.
                    if (s.appLockEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        setContent { AppRoot(activity = this, deepLink = deepLink) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val book = intent?.getStringExtra(DeepLinks.EXTRA_OPEN_BOOK)
        val dest = intent?.getStringExtra(DeepLinks.EXTRA_DESTINATION)
        if (book != null || dest != null) deepLink.value = DeepLinkRequest(book, dest, System.nanoTime())
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val s = prefs.settings.first()
            if (firstStart) { lock.lockOnLaunch(s.appLockEnabled); firstStart = false }
            else lock.onAppForegrounded(s.appLockEnabled, s.lockImmediately)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) lock.onAppBackgrounded()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Volume keys must be caught before the system changes the volume.
        val volume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volume) return pageTurns.handle(event, settings.volumeKeysTurnPages) || super.dispatchKeyEvent(event)
        // Other keys go to the focused control first (so typing a space in a search box still works).
        if (super.dispatchKeyEvent(event)) return true
        return pageTurns.handle(event, volumeKeysEnabled = false)
    }
}
