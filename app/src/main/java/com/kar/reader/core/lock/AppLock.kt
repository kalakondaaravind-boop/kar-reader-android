package com.kar.reader.core.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App lock and private-collection lock. Authentication uses Android's BiometricPrompt with fingerprint,
 * face or the device PIN/pattern; KAR READER never sees or stores biometric data.
 * Limitation (documented): the lock hides the app's screens; it does not encrypt data on disk beyond
 * Android's own device encryption.
 */
@Singleton
class AppLockManager @Inject constructor() {

    private val _appLocked = MutableStateFlow(false)
    val appLocked: StateFlow<Boolean> = _appLocked.asStateFlow()

    private val _privateUnlocked = MutableStateFlow(false)
    val privateUnlocked: StateFlow<Boolean> = _privateUnlocked.asStateFlow()

    private var backgroundedAt: Long = 0

    fun onAppBackgrounded() {
        backgroundedAt = System.currentTimeMillis()
        _privateUnlocked.value = false
    }

    /** Locks on return when enabled and the user was away longer than the grace period. */
    fun onAppForegrounded(lockEnabled: Boolean, lockImmediately: Boolean) {
        if (!lockEnabled) { _appLocked.value = false; return }
        val away = System.currentTimeMillis() - backgroundedAt
        if (backgroundedAt == 0L || lockImmediately || away > 60_000) _appLocked.value = true
    }

    fun lockOnLaunch(lockEnabled: Boolean) { if (lockEnabled) _appLocked.value = true }

    fun lockPrivateNow() { _privateUnlocked.value = false }

    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(activity: FragmentActivity, title: String, subtitle: String, onSuccess: () -> Unit, onFailure: (String) -> Unit = {}) {
        if (!canAuthenticate(activity)) {
            onFailure("Set up a screen lock (PIN, pattern, fingerprint or face) in Android settings to use this.")
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(errString.toString())
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    fun unlockApp(activity: FragmentActivity, onFailure: (String) -> Unit) =
        authenticate(activity, "Unlock KAR READER", "Use your fingerprint, face or device PIN", { _appLocked.value = false }, onFailure)

    fun unlockPrivate(activity: FragmentActivity, onFailure: (String) -> Unit) =
        authenticate(activity, "Private collection", "Unlock to see private books and notes", { _privateUnlocked.value = true }, onFailure)
}
