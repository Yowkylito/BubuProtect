package com.personal.bubuprotect.ui

import kotlinx.serialization.Serializable


@Serializable
sealed class Routes() {
    @Serializable
    object HomeRoute : Routes()

}