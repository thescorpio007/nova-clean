package com.novaclean.app.core

enum class Verdict { UNKNOWN, SAFE, CAUTION, NEVER }

data class AppEntry(
    val pkg: String,
    val label: String,
    val lastUsedMinAgo: Long?,          // null = unknown
    val running: Boolean,
    val protectedReason: String? = null,
    val verdict: Verdict = Verdict.UNKNOWN,
    val aiReason: String = "",
    val selected: Boolean = false,
    val system: Boolean = false,
) {
    val isProtected: Boolean get() = protectedReason != null
}
