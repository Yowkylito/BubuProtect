package com.personal.bubuprotect

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.personal.bubuprotect.di.appModule
import com.personal.bubuprotect.session.VaultSession
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MainApplication : Application() {

    private val session: VaultSession by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MainApplication)
            modules(appModule)
        }
        observeProcessLifecycle()
        observeScreenOff()
    }

    /**
     * Auto-lock on backgrounding, driven by the *process* lifecycle rather than the activity's.
     * An activity-level observer fires on every rotation and every dialog, so it would either lock
     * constantly or need a pile of exceptions; `ProcessLifecycleOwner` reports only genuine
     * app-level transitions.
     */
    private fun observeProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = session.onMovedToBackground()
            override fun onStart(owner: LifecycleOwner) {
                session.onReturnedToForeground()
            }
        })
    }

    /**
     * Screen off means the phone went into a pocket or was taken - the theft case. No grace period
     * applies, so this locks immediately rather than starting the background timer.
     *
     * `ACTION_SCREEN_OFF` is not deliverable to manifest-declared receivers, so it has to be
     * registered at runtime and kept for the process lifetime.
     */
    private fun observeScreenOff() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) session.onScreenOff()
            }
        }
        // NOT_EXPORTED so no other app can forge a screen-off and drive this app's lock state.
        // ACTION_SCREEN_OFF is a protected broadcast, so the flag is not strictly required, but
        // being explicit keeps the receiver correct if the filter ever gains a non-system action -
        // and from Android 14 an unflagged registration for such an action throws.
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
