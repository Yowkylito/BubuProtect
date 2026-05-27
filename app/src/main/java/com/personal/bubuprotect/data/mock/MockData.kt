package com.personal.bubuprotect.data.mock

import com.personal.bubuprotect.domain.model.PasswordEntry

val mockPasswordEntries = listOf(
    PasswordEntry(
        id = "1",
        label = "Gmail",
        username = "bubu@gmail.com",
        password = "Str0ng#Gmail!24",
        website = "gmail.com",
        category = "Email"
    ),
    PasswordEntry(
        id = "2",
        label = "Instagram",
        username = "@bubu.protect",
        password = "Insta\$ecure#99",
        website = "instagram.com",
        category = "Social"
    ),
    PasswordEntry(
        id = "3",
        label = "Bank Account",
        username = "bubu123456",
        password = "Bank\$3cur3!2024",
        category = "Finance"
    ),
    PasswordEntry(
        id = "4",
        label = "Netflix",
        username = "bubu@gmail.com",
        password = "N3tfl!x#2024",
        website = "netflix.com",
        category = "Entertainment"
    ),
    PasswordEntry(
        id = "5",
        label = "GitHub",
        username = "bubu-protect",
        password = "G!tHub#Dev2024",
        website = "github.com",
        category = "Developer"
    )
)
