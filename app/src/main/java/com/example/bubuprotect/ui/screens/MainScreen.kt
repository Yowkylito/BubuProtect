package com.example.bubuprotect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.bubuprotect.MainNavHost
import com.example.bubuprotect.services.BiometricHelper
import com.example.bubuprotect.ui.components.Primary01


@Composable
fun MainScreen(
    biometricHelper: BiometricHelper,
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Primary01)
    ) { innerPadding ->
        MainNavHost(
            biometricHelper = biometricHelper,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        )
    }
}

