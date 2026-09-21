package com.v2ray.ang.dto

data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseTag: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val downloadFileName: String? = null,
    val error: String? = null,
    val isPreRelease: Boolean = false
)
