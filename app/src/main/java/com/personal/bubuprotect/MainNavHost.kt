package com.personal.bubuprotect

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.personal.bubuprotect.services.BiometricHelper
import com.personal.bubuprotect.ui.Routes
import com.personal.bubuprotect.ui.screens.FaceRecognitionScreen
import com.personal.bubuprotect.ui.screens.HomeScreen

@Composable
fun MainNavHost(
    navController: NavHostController = rememberNavController(),
    biometricHelper: BiometricHelper,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HomeRoute,
        modifier = modifier
    ) {
        composable<Routes.HomeRoute> { backStackEntry ->
            var isPasswordVisible by remember { mutableStateOf(false) }
            val context = LocalContext.current
            val activity = context as FragmentActivity

            // Listen for success from Face Recognition screen
            val recognitionSuccess =
                backStackEntry.savedStateHandle.get<Boolean>("success") ?: false
            LaunchedEffect(recognitionSuccess) {
                if (recognitionSuccess) {
                    isPasswordVisible = true
                    backStackEntry.savedStateHandle.remove<Boolean>("success")
                }
            }

            HomeScreen(
                isPasswordVisible = isPasswordVisible,
                onVisibilityChange = {
                    if (isPasswordVisible) {
                        isPasswordVisible = false
                    } else {
                        // Trigger Biometric Authentication
                        if (biometricHelper.canAuthenticate()) {
                            val promptInfo = biometricHelper.buildPromptInfo(
                                title = "Authentication Required",
                                subtitle = "Confirm your identity to show password"
                            )

                            val biometricPrompt = biometricHelper.createBiometricPrompt(
                                activity = activity,
                                onSuccess = {
                                    isPasswordVisible = true
                                },
                                onError = { code, msg ->
                                    Log.e("BIOMETRICS", "Error ($code): $msg")
                                },
                                onFailed = {
                                    Log.d("BIOMETRICS", "Authentication failed")
                                }
                            )
                            biometricPrompt.authenticate(promptInfo)
                        } else {
                            // Fallback to Face Recognition if Biometrics are not configured
                            navController.navigate(Routes.FaceRecognitionRoute)
                        }
                    }
                }
            )
        }

        composable<Routes.FaceRecognitionRoute> {
            FaceRecognitionScreen(
                onFaceRecognized = {
                    navController.previousBackStackEntry?.savedStateHandle?.set("success", true)
                    navController.popBackStack()
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}