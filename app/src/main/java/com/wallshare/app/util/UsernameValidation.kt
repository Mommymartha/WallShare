package com.wallshare.app.util

/**
 * Mirrors the `username_format` check constraint from the Step 2 migration
 * (^[a-z0-9_]{3,20}$). This is purely for fast, local feedback before making
 * a network call - claim_username() re-validates server-side regardless, so
 * this never needs to be perfectly in sync to stay secure, only to stay
 * helpful.
 */
private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,20}$")

fun isValidUsernameFormat(username: String): Boolean = USERNAME_REGEX.matches(username)
