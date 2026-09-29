package com.wallshare.app.data.model

import kotlinx.serialization.Serializable

/**
 * Mirrors a row in the `profiles` table. `username` is null until the user
 * completes onboarding via claim_username() (see AuthRepository).
 */
@Serializable
data class Profile(
    val id: String,
    val username: String? = null,
    val display_name: String? = null,
)
