package com.personal.bubuprotect

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.personal.bubuprotect.services.BiometricHelper
import com.personal.bubuprotect.ui.Routes
import com.personal.bubuprotect.ui.screens.HomeScreen

@Composable
fun MainNavHost(
    biometricHelper: BiometricHelper,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = rememberNavController(),
        startDestination = Routes.HomeRoute,
        modifier = modifier
    ) {

        composable<Routes.HomeRoute> {
            var isPasswordVisible by remember { mutableStateOf(false) }
            val context = LocalContext.current
            val activity = context as FragmentActivity
            HomeScreen(
                isPasswordVisible = isPasswordVisible,
                onVisibilityChange = {
                    if (biometricHelper.canAuthenticate()) {
                        val promptInfo = biometricHelper.buildPromptInfo(
                            title = "Prove that you are my Bubu!",
                            subtitle = "Authenticate using your fingerprint"
                        )

                        val biometricPrompt = biometricHelper.createBiometricPrompt(
                            activity = activity,
                            onSuccess = {
                                isPasswordVisible = !isPasswordVisible
                            },
                            onError = { code, msg ->
                                Log.e("BIOMETRICS", "Error: $msg")
                            },
                            onFailed = {
                                Log.e("BIOMETRICS", "Authentication failed")
                            }
                        )
                        if (!isPasswordVisible) {
                            biometricPrompt.authenticate(promptInfo)
                        } else {
                            isPasswordVisible = !isPasswordVisible
                        }
                    }

                }
            )
        }
    }
}

