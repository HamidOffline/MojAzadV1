package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {

    /*
     * MojAzad V3
     *
     * Supported version examples:
     *
     * 3
     * 3.2
     * 3.2.8
     * V3.2.8
     * MojAzad.V3
     * MojAzad.V3-beta1
     * MojAzad.V3.2.8
     * MojAzad.V3.2.8-beta2
     * MojAzad.V4.1.3-rc1
     *
     * Stable versions are considered newer than
     * prerelease versions with the same numeric version.
     */
    private val VERSION_REGEX =
        Regex(
            """(?i)(?:^|[^0-9])v?(\d+(?:\.\d+){0,3})(?:[-_.]?(alpha|beta|rc|preview)[-_.]?(\d*))?"""
        )

    private const val VERSION_STAGE_ALPHA =
        1

    private const val VERSION_STAGE_BETA =
        2

    private const val VERSION_STAGE_RC =
        3

    private const val VERSION_STAGE_STABLE =
        4

    private data class ParsedVersion(
        val numbers: List<Int>,
        val stage: Int,
        val preReleaseNumber: Int,
        val displayVersion: String
    )

    suspend fun checkForUpdate(
        includePreRelease: Boolean = false
    ): CheckUpdateResult =
        withContext(
            Dispatchers.IO
        ) {

            val url =
                if (
                    includePreRelease
                ) {

                    AppConfig.APP_API_URL

                } else {

                    AppConfig.APP_API_URL
                        .concatUrl(
                            "latest"
                        )
                }

            val proxyUsername =
                SettingsManager
                    .getSocksUsername()

            val proxyPassword =
                SettingsManager
                    .getSocksPassword()

            var response =
                HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = url,
                        timeout = 5000
                    )
                )

            /*
             * First try normal network.
             *
             * If GitHub is not reachable directly,
             * retry using MojAzad's local HTTP proxy.
             */
            if (
                response.isNullOrEmpty()
            ) {

                val httpPort =
                    SettingsManager
                        .getHttpPort()

                response =
                    HttpUtil.getUrlContent(
                        UrlContentRequest(
                            url = url,
                            timeout = 5000,
                            httpPort = httpPort,
                            proxyUsername = proxyUsername,
                            proxyPassword = proxyPassword
                        )
                    )
                        ?: throw IllegalStateException(
                            "Failed to get update information"
                        )
            }

            val currentVersion =
                parseVersion(
                    BuildConfig.VERSION_NAME
                )
                    ?: throw IllegalStateException(
                        "Unsupported current version: ${BuildConfig.VERSION_NAME}"
                    )

            val latestRelease =
                if (
                    includePreRelease
                ) {

                    /*
                     * GitHub /releases returns both stable
                     * and prerelease releases.
                     *
                     * Do not simply select firstOrNull().
                     * Instead choose the highest MojAzad
                     * version according to our version parser.
                     */
                    val releases =
                        JsonUtil.fromJsonSafe(
                            response,
                            Array<GitHubRelease>::class.java
                        )
                            ?.toList()
                            .orEmpty()

                    findNewestRelease(
                        releases
                    )
                        ?: throw IllegalStateException(
                            "No compatible MojAzad release found"
                        )

                } else {

                    JsonUtil.fromJsonSafe(
                        response,
                        GitHubRelease::class.java
                    )
                }

            if (
                latestRelease == null
            ) {

                return@withContext CheckUpdateResult(
                    hasUpdate = false
                )
            }

            val latestVersion =
                parseVersion(
                    latestRelease.tagName
                )
                    ?: throw IllegalStateException(
                        "Unsupported release version: ${latestRelease.tagName}"
                    )

            LogUtil.i(
                AppConfig.TAG,
                "MojAzad update: release=${latestRelease.tagName}, " +
                    "parsed=${latestVersion.displayVersion}, " +
                    "current=${BuildConfig.VERSION_NAME}"
            )

            if (
                compareVersions(
                    latestVersion,
                    currentVersion
                ) <= 0
            ) {

                return@withContext CheckUpdateResult(
                    hasUpdate = false
                )
            }

            /*
             * Select APK matching the device ABI.
             */
            val downloadAsset =
                getDownloadAsset(
                    latestRelease
                )

            CheckUpdateResult(
                hasUpdate = true,
                latestVersion =
                    latestVersion.displayVersion,
                releaseTag =
                    latestRelease.tagName,
                releaseNotes =
                    latestRelease.body,
                downloadUrl =
                    downloadAsset.browserDownloadUrl,
                downloadFileName =
                    downloadAsset.name,
                isPreRelease =
                    latestRelease.prerelease
            )
        }

    /**
     * Finds the numerically newest release.
     *
     * This makes prerelease checking independent
     * from GitHub array ordering.
     */
    private fun findNewestRelease(
        releases: List<GitHubRelease>
    ): GitHubRelease? {

        var newestRelease:
            GitHubRelease? = null

        var newestVersion:
            ParsedVersion? = null

        releases.forEach {
                release ->

            val parsed =
                parseVersion(
                    release.tagName
                )
                    ?: return@forEach

            if (
                newestVersion == null ||
                compareVersions(
                    parsed,
                    newestVersion!!
                ) > 0
            ) {

                newestRelease =
                    release

                newestVersion =
                    parsed
            }
        }

        return newestRelease
    }

    /**
     * Parses MojAzad version strings safely.
     *
     * Examples:
     *
     * MojAzad.V3
     * -> 3
     *
     * MojAzad.V3-beta1
     * -> 3-beta1
     *
     * MojAzad.V3.2.8
     * -> 3.2.8
     *
     * MojAzad.V3.2.8-beta2
     * -> 3.2.8-beta2
     */
    private fun parseVersion(
        rawVersion: String
    ): ParsedVersion? {

        val match =
            VERSION_REGEX.find(
                rawVersion.trim()
            )
                ?: return null

        val numberPart =
            match.groupValues
                .getOrNull(
                    1
                )
                .orEmpty()

        if (
            numberPart.isBlank()
        ) {

            return null
        }

        val numbers =
            numberPart
                .split(
                    "."
                )
                .map {

                    it.toIntOrNull()
                        ?: return null
                }

        val stageName =
            match.groupValues
                .getOrNull(
                    2
                )
                .orEmpty()
                .lowercase()

        val preReleaseNumberText =
            match.groupValues
                .getOrNull(
                    3
                )
                .orEmpty()

        val preReleaseNumber =
            preReleaseNumberText
                .toIntOrNull()
                ?: 0

        val stage =
            when (
                stageName
            ) {

                "alpha" ->
                    VERSION_STAGE_ALPHA

                "beta",
                "preview" ->
                    VERSION_STAGE_BETA

                "rc" ->
                    VERSION_STAGE_RC

                else ->
                    VERSION_STAGE_STABLE
            }

        val displayVersion =
            if (
                stageName.isBlank()
            ) {

                numberPart

            } else {

                buildString {

                    append(
                        numberPart
                    )

                    append(
                        "-"
                    )

                    append(
                        stageName
                    )

                    if (
                        preReleaseNumberText.isNotBlank()
                    ) {

                        append(
                            preReleaseNumber
                        )
                    }
                }
            }

        return ParsedVersion(
            numbers = numbers,
            stage = stage,
            preReleaseNumber = preReleaseNumber,
            displayVersion = displayVersion
        )
    }

    /**
     * Compares two parsed versions.
     *
     * Examples:
     *
     * 3.2.8-beta1 < 3.2.8-beta2
     * 3.2.8-beta2 < 3.2.8-rc1
     * 3.2.8-rc1   < 3.2.8
     * 3.2.8       < 3.2.9
     * 3.9.9       < 4.0.0
     */
    private fun compareVersions(
        version1: ParsedVersion,
        version2: ParsedVersion
    ): Int {

        val maxParts =
            maxOf(
                version1.numbers.size,
                version2.numbers.size
            )

        for (
            index in
            0 until maxParts
        ) {

            val number1 =
                version1.numbers
                    .getOrElse(
                        index
                    ) {
                        0
                    }

            val number2 =
                version2.numbers
                    .getOrElse(
                        index
                    ) {
                        0
                    }

            if (
                number1 != number2
            ) {

                return number1
                    .compareTo(
                        number2
                    )
            }
        }

        /*
         * Same numeric version.
         *
         * alpha < beta < rc < stable
         */
        if (
            version1.stage !=
            version2.stage
        ) {

            return version1.stage
                .compareTo(
                    version2.stage
                )
        }

        /*
         * Both are stable.
         */
        if (
            version1.stage ==
            VERSION_STAGE_STABLE
        ) {

            return 0
        }

        /*
         * beta1 < beta2
         * rc1   < rc2
         */
        return version1
            .preReleaseNumber
            .compareTo(
                version2.preReleaseNumber
            )
    }

    /**
     * Selects the best APK for the current device.
     *
     * Priority:
     *
     * 1. Correct distribution channel
     * 2. Non-debug APK
     * 3. First supported device ABI
     * 4. Universal APK fallback
     */
    private fun getDownloadAsset(
        release: GitHubRelease
    ): GitHubRelease.Asset {

        val apkAssets =
            release.assets
                .filter {

                    it.name.endsWith(
                        ".apk",
                        ignoreCase = true
                    )
                }

        if (
            apkAssets.isEmpty()
        ) {

            throw IllegalStateException(
                "No APK found in this release"
            )
        }

        val isFdroidBuild =
            BuildConfig.APPLICATION_ID
                .contains(
                    "fdroid",
                    ignoreCase = true
                )

        val channelAssets =
            if (
                isFdroidBuild
            ) {

                apkAssets.filter {

                    it.name.contains(
                        "fdroid",
                        ignoreCase = true
                    )
                }

            } else {

                apkAssets.filter {

                    !it.name.contains(
                        "fdroid",
                        ignoreCase = true
                    )
                }
            }
                .ifEmpty {

                    apkAssets
                }

        /*
         * Never prefer debug packages for updates.
         */
        val releaseAssets =
            channelAssets
                .filter {

                    !it.name.contains(
                        "debug",
                        ignoreCase = true
                    )
                }
                .ifEmpty {

                    channelAssets
                }

        /*
         * Android returns ABIs ordered by preference.
         *
         * Example:
         * arm64-v8a
         * armeabi-v7a
         */
        Build.SUPPORTED_ABIS
            .forEach {
                    abi ->

                releaseAssets
                    .firstOrNull {

                        assetMatchesAbi(
                            assetName =
                                it.name,
                            abi =
                                abi
                        )
                    }
                    ?.let {

                        LogUtil.i(
                            AppConfig.TAG,
                            "Selected update APK: ${it.name} for ABI $abi"
                        )

                        return it
                    }
            }

        /*
         * Universal APK fallback.
         */
        releaseAssets
            .firstOrNull {

                val name =
                    it.name.lowercase()

                name.contains(
                    "universal"
                ) ||
                    name.contains(
                        "all-arch"
                    ) ||
                    name.contains(
                        "all_arch"
                    )
            }
            ?.let {

                LogUtil.i(
                    AppConfig.TAG,
                    "Selected universal update APK: ${it.name}"
                )

                return it
            }

        throw IllegalStateException(
            "No compatible APK found for ${Build.SUPPORTED_ABIS.joinToString()}"
        )
    }

    /**
     * Matches common APK architecture naming formats.
     */
    private fun assetMatchesAbi(
        assetName: String,
        abi: String
    ): Boolean {

        val name =
            assetName.lowercase()

        return when (
            abi.lowercase()
        ) {

            "arm64-v8a" ->

                name.contains(
                    "arm64-v8a"
                ) ||
                    name.contains(
                        "arm64"
                    ) ||
                    name.contains(
                        "aarch64"
                    )

            "armeabi-v7a" ->

                name.contains(
                    "armeabi-v7a"
                ) ||
                    name.contains(
                        "armv7a"
                    ) ||
                    name.contains(
                        "armv7"
                    )

            "x86_64" ->

                name.contains(
                    "x86_64"
                ) ||
                    name.contains(
                        "x86-64"
                    ) ||
                    name.contains(
                        "amd64"
                    )

            "x86" ->

                Regex(
                    """(^|[_\-.])x86([_\-.]|$)""",
                    RegexOption.IGNORE_CASE
                )
                    .containsMatchIn(
                        name
                    )

            else ->

                name.contains(
                    abi,
                    ignoreCase = true
                )
        }
    }
}
