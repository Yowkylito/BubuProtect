package com.personal.bubuprotect

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.personal.bubuprotect.services.BiometricHelper
import com.personal.bubuprotect.ui.Routes
import com.personal.bubuprotect.ui.screens.FaceRecognitionScreen
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

        setContent {
            val navController = rememberNavController()
            val context = LocalContext.current
            val activity = context as FragmentActivity

            // Use NavHost to handle navigation between Welcome and Face Recognition
            NavHost(navController = navController, startDestination = "welcome") {
                composable("welcome") { backStackEntry ->
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        WelcomeScreen(modifier = Modifier.padding(innerPadding)) {
                            navController.navigate(Routes.FaceRecognitionRoute)
                        }
                        // Use the backStackEntry provided by the composable scope
                        val recognitionSuccess =
                            backStackEntry.savedStateHandle.get<Boolean>("success") ?: false

                        LaunchedEffect(recognitionSuccess) {
                            if (recognitionSuccess) {
                                backStackEntry.savedStateHandle.remove<Boolean>("success")
                                if (biometricHelper.canAuthenticate()) {
                                    val promptInfo = biometricHelper.buildPromptInfo(
                                        title = "Second Factor Authentication",
                                        subtitle = "Authenticate using your fingerprint"
                                    )

                                    val biometricPrompt = biometricHelper.createBiometricPrompt(
                                        activity = activity,
                                        onSuccess = {
                                            setContent {
                                                val mainNavController = rememberNavController()
                                                MainScreen(mainNavController, biometricHelper)
                                            }
                                        },
                                        onError = { code, msg ->
                                            Log.e("BIOMETRICS", "Error: $msg")
                                        },
                                        onFailed = {
                                            Log.e("BIOMETRICS", "Authentication failed")
                                        }
                                    )
                                    biometricPrompt.authenticate(promptInfo)
                                }
                            }
                        }
                    }
                }

                // Add the FaceRecognitionRoute to this NavHost as well
                composable<Routes.FaceRecognitionRoute> {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        // Assuming you have FaceRecognitionScreen available
                        FaceRecognitionScreen(
                            modifier = Modifier.padding(innerPadding),
                            onFaceRecognized = {
                                navController.previousBackStackEntry?.savedStateHandle?.set(
                                    "success",
                                    true
                                )
                                navController.popBackStack()
                            },
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }


    }

}
