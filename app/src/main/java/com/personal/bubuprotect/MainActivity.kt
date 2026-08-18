package com.personal.bubuprotect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import coil.ImageLoader
import com.personal.bubuprotect.core.security.SecureWindow
import com.personal.bubuprotect.session.VaultSession
import com.personal.bubuprotect.ui.BubuApp
import com.personal.bubuprotect.ui.components.LocalBubuImageLoader
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import org.koin.android.ext.android.inject

/**
 * The only activity.
 *
 * `AppCompatActivity` (and so `FragmentActivity`) because `BiometricPrompt` attaches itself as a
 * fragment - that is also what lets the prompt survive a rotation mid-authentication. Swapping the
 * base class for `ComponentActivity` would break every authentication in the app at runtime rather
 * than at compile time.
 */
class MainActivity : AppCompatActivity() {

    private val session: VaultSession by inject()
    private val imageLoader: ImageLoader by inject()

    /**
     * Screen off is the device-theft signal: no grace period, lock immediately.
     *
     * `ACTION_SCREEN_OFF` cannot be declared in the manifest - the system only delivers it to
     * runtime-registered receivers - so it is bound to the activity's whole lifetime rather than to
     * `onStart`/`onStop`. Registering it in `onStart` would be too late: turning the screen off
     * *causes* `onStop`, and the two would race.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) session.onScreenOff()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Before any content exists, so there is no frame that could be captured unprotected.
        SecureWindow.harden(this)
        enableEdgeToEdge()

        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        lifecycle.addObserver(AutoLock(session))

        setContent {
            BubuProtectTheme {
                CompositionLocalProvider(LocalBubuImageLoader provides imageLoader) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        BubuApp()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }
}

/**
 * The background timeout.
 *
 * `onStop`/`onStart` rather than `onPause`/`onResume`: a biometric prompt pauses the activity, and
 * locking the vault the instant the fingerprint dialog appeared would make unlocking impossible.
 *
 * The grace period itself lives in [VaultSession], measured against the monotonic clock - see
 * [VaultSession.onReturnedToForeground] for why it is a minute rather than zero.
 */
private class AutoLock(private val session: VaultSession) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) = session.onMovedToBackground()

    override fun onStart(owner: LifecycleOwner) {
        session.onReturnedToForeground()
    }
}
