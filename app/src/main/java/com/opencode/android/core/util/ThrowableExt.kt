package com.opencode.android.core.util

fun Throwable.safeMessage(fallback: String = "Unknown error"): String = message?.takeIf { it.isNotBlank() } ?: fallback
