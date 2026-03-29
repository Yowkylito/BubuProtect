package com.personal.bubuprotect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.personal.bubuprotect.MainNavHost
import com.personal.bubuprotect.services.BiometricHelper
import com.personal.bubuprotect.ui.components.Primary01


@Composable
fun MainScreen(
    navController: NavHostController,
    biometricHelper: BiometricHelper,
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Primary01)
    ) { innerPadding ->
        MainNavHost(
            navController=navController,
            biometricHelper = biometricHelper,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        )
    }
}

