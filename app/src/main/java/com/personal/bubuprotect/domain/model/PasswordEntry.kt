package com.personal.bubuprotect.domain.model

data class PasswordEntry(
    val id: String,
    val label: String,
    val username: String,
    val password: String,
    val website: String? = null,
    val category: String = "General"
)
