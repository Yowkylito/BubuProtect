package com.personal.bubuprotect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.ui.theme.bubu

/** Branded feedback surface shared by every screen that emits transient notices. */
@Composable
fun BubuSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val shape = MaterialTheme.shapes.large
        Snackbar(
            snackbarData = data,
            modifier = Modifier.border(
                BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.8f)),
                shape
            ),
            shape = shape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = MaterialTheme.bubu.champagne
        )
    }
}
