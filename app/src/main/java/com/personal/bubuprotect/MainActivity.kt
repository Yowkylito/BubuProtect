package com.personal.bubuprotect

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.personal.bubuprotect.services.BiometricHelper
import com.personal.bubuprotect.ui.screens.MainScreen
import com.personal.bubuprotect.ui.screens.WelcomeScreen

class MainActivity : AppCompatActivity() {
    private lateinit var biometricHelper: BiometricHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        //Prevent Overlays (Anti-Tapjacking)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        biometricHelper = BiometricHelper(this)

        if (biometricHelper.canAuthenticate()) {
            val promptInfo = biometricHelper.buildPromptInfo(
                title = "Prove that you are my Bubu!",
                subtitle = "Authenticate using your fingerprint"
            )

            val biometricPrompt = biometricHelper.createBiometricPrompt(
                this,
                onSuccess = {
                    // ✅ Success → show MainScreen
                    setContent { MainScreen(biometricHelper) }
                },
                onError = { code, msg ->
                    Log.e("BIOMETRICS", "Error: $msg")
                },
                onFailed = {
                    Log.e("BIOMETRICS", "Authentication failed")
                }
            )

            setContent {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WelcomeScreen(modifier = Modifier.padding(innerPadding)) {
                        biometricPrompt.authenticate(promptInfo)
                    }
                }
            }
        }
    }
}
