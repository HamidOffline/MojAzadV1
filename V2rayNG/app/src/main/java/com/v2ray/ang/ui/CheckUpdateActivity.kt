package com.v2ray.ang.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityCheckUpdateBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class CheckUpdateActivity : BaseActivity() {

    companion object {

        /*
         * MojAzad V3
         *
         * Update APKs are downloaded only to
         * the application's private cache.
         */
        private const val UPDATE_CACHE_DIR =
            "mojazad_updates"

        private const val APK_MIME_TYPE =
            "application/vnd.android.package-archive"

        /*
         * APK files can be much larger than normal
         * API responses, so use a longer timeout.
         */
        private const val APK_DOWNLOAD_TIMEOUT_MS =
            120_000
    }

    private val binding by lazy {

        ActivityCheckUpdateBinding.inflate(
            layoutInflater
        )
    }

    /*
     * If Android requires permission to install
     * unknown apps, keep the downloaded APK here
     * until the user returns from Settings.
     */
    private var pendingInstallFile:
        File? = null

    /*
     * Prevent multiple simultaneous update checks
     * or downloads.
     */
    private var updateOperationRunning =
        false

    private val requestInstallPermission =
        registerForActivityResult(
            ActivityResultContracts
                .StartActivityForResult()
        ) {

            val apkFile =
                pendingInstallFile

            if (
                apkFile == null ||
                !apkFile.exists()
            ) {

                pendingInstallFile =
                    null

                showUpdateStatus(
                    "فایل آپدیت پیدا نشد",
                    false
                )

                return@registerForActivityResult
            }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O &&
                !packageManager
                    .canRequestPackageInstalls()
            ) {

                showUpdateStatus(
                    "برای نصب، اجازه «نصب برنامه ناشناس» را برای MojAzad فعال کنید",
                    false
                )

                toastError(
                    "اجازه نصب برنامه از این منبع فعال نشد"
                )

                return@registerForActivityResult
            }

            pendingInstallFile =
                null

            showUpdateStatus(
                "آپدیت آماده نصب است",
                false
            )

            openAndroidInstaller(
                apkFile
            )
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(
                R.string.update_check_for_update
            )
        )

        binding.layoutCheckUpdate
            .setOnClickListener {

                if (
                    updateOperationRunning
                ) {

                    return@setOnClickListener
                }

                checkForUpdates(
                    binding.checkPreRelease
                        .isChecked
                )
            }

        binding.checkPreRelease
            .setOnCheckedChangeListener {
                    _,
                    isChecked ->

                MmkvManager.encodeSettings(
                    AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE,
                    isChecked
                )
            }

        binding.checkPreRelease.isChecked =
            MmkvManager.decodeSettingsBool(
                AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE,
                false
            )

        "v${BuildConfig.VERSION_NAME} (${CoreNativeManager.getLibVersion()})"
            .also {

                binding.tvVersion.text =
                    it
            }

        hideUpdateStatus()

        checkForUpdates(
            binding.checkPreRelease
                .isChecked
        )
    }

    /*
     * =========================================================
     * MojAzad V3 Update Status UI
     * =========================================================
     */

    private fun showUpdateStatus(
        message: String,
        showProgress: Boolean
    ) {

        binding.layoutUpdateStatus.visibility =
            View.VISIBLE

        binding.tvUpdateStatus.text =
            message

        binding.progressUpdate.visibility =
            if (
                showProgress
            ) {

                View.VISIBLE

            } else {

                View.GONE
            }

        if (
            showProgress
        ) {

            binding.progressUpdate.isIndeterminate =
                true
        }
    }

    private fun showDownloadProgress(
        version: String,
        downloadedBytes: Long,
        totalBytes: Long,
        progressPercent: Int?
    ) {

        binding.layoutUpdateStatus.visibility =
            View.VISIBLE

        binding.progressUpdate.visibility =
            View.VISIBLE

        if (
            progressPercent != null &&
            totalBytes > 0L
        ) {

            binding.progressUpdate.isIndeterminate =
                false

            binding.progressUpdate.progress =
                progressPercent

            binding.tvUpdateStatus.text =
                buildString {

                    append(
                        "در حال دانلود $version...\n"
                    )

                    append(
                        progressPercent
                    )

                    append(
                        "% • "
                    )

                    append(
                        formatFileSize(
                            downloadedBytes
                        )
                    )

                    append(
                        " / "
                    )

                    append(
                        formatFileSize(
                            totalBytes
                        )
                    )
                }

        } else {

            binding.progressUpdate.isIndeterminate =
                true

            binding.tvUpdateStatus.text =
                buildString {

                    append(
                        "در حال دانلود $version...\n"
                    )

                    append(
                        formatFileSize(
                            downloadedBytes
                        )
                    )
                }
        }
    }

    private fun hideUpdateStatus() {

        binding.layoutUpdateStatus.visibility =
            View.GONE

        binding.progressUpdate.visibility =
            View.GONE
    }

    private fun setUpdateOperationRunning(
        running: Boolean
    ) {

        updateOperationRunning =
            running

        binding.layoutCheckUpdate.isEnabled =
            !running

        binding.layoutCheckUpdate.isClickable =
            !running

        binding.checkPreRelease.isEnabled =
            !running
    }

    private fun formatFileSize(
        bytes: Long
    ): String {

        val safeBytes =
            bytes.coerceAtLeast(
                0L
            )

        val kb =
            1024.0

        val mb =
            kb * 1024.0

        val gb =
            mb * 1024.0

        return when {

            safeBytes >=
                gb -> {

                String.format(
                    Locale.US,
                    "%.2f GB",
                    safeBytes / gb
                )
            }

            safeBytes >=
                mb -> {

                String.format(
                    Locale.US,
                    "%.1f MB",
                    safeBytes / mb
                )
            }

            safeBytes >=
                kb -> {

                String.format(
                    Locale.US,
                    "%.1f KB",
                    safeBytes / kb
                )
            }

            else -> {

                "$safeBytes B"
            }
        }
    }

    /*
     * =========================================================
     * Check update
     * =========================================================
     */

    private fun checkForUpdates(
        includePreRelease: Boolean
    ) {

        if (
            updateOperationRunning
        ) {

            return
        }

        setUpdateOperationRunning(
            true
        )

        showUpdateStatus(
            "در حال بررسی نسخه جدید...",
            true
        )

        toast(
            R.string.update_checking_for_update
        )

        showLoading()

        lifecycleScope.launch {

            try {

                val result =
                    UpdateCheckerManager
                        .checkForUpdate(
                            includePreRelease
                        )

                if (
                    result.hasUpdate
                ) {

                    val version =
                        result.releaseTag
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: result.latestVersion
                            ?: "نسخه جدید"

                    showUpdateStatus(
                        "$version آماده دانلود است",
                        false
                    )

                    showUpdateDialog(
                        result
                    )

                } else {

                    showUpdateStatus(
                        "MojAzad به‌روز است",
                        false
                    )

                    toastSuccess(
                        R.string.update_already_latest_version
                    )
                }

            } catch (
                e: Exception
            ) {

                LogUtil.e(
                    AppConfig.TAG,
                    "Failed to check for updates: ${e.message}",
                    e
                )

                showUpdateStatus(
                    "بررسی آپدیت انجام نشد",
                    false
                )

                toastError(
                    e.message
                        ?: getString(
                            R.string.toast_failure
                        )
                )

            } finally {

                hideLoading()

                setUpdateOperationRunning(
                    false
                )
            }
        }
    }

    /*
     * =========================================================
     * MojAzad V3 In-App Update
     * =========================================================
     */

    private fun showUpdateDialog(
        result: CheckUpdateResult
    ) {

        val versionTitle =
            result.releaseTag
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: result.latestVersion
                ?: "نسخه جدید"

        val message =
            buildString {

                if (
                    result.isPreRelease
                ) {

                    append(
                        "نسخه آزمایشی\n\n"
                    )
                }

                if (
                    !result.releaseNotes
                        .isNullOrBlank()
                ) {

                    append(
                        result.releaseNotes
                    )

                } else {

                    append(
                        "نسخه جدید MojAzad آماده دانلود است."
                    )
                }
            }

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "آپدیت $versionTitle"
            )
            .setMessage(
                message
            )
            .setPositiveButton(
                R.string.update_now
            ) {
                    _,
                    _ ->

                downloadAndInstallUpdate(
                    result
                )
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    /**
     * Downloads the selected ABI APK inside MojAzad.
     *
     * No browser is opened.
     */
    private fun downloadAndInstallUpdate(
        result: CheckUpdateResult
    ) {

        if (
            updateOperationRunning
        ) {

            return
        }

        val downloadUrl =
            result.downloadUrl
                ?.trim()
                .orEmpty()

        if (
            downloadUrl.isBlank()
        ) {

            showUpdateStatus(
                "لینک دانلود آپدیت پیدا نشد",
                false
            )

            toastError(
                "لینک دانلود آپدیت پیدا نشد"
            )

            return
        }

        val version =
            result.releaseTag
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: result.latestVersion
                ?: "نسخه جدید"

        setUpdateOperationRunning(
            true
        )

        showUpdateStatus(
            "در حال آماده‌سازی دانلود $version...",
            true
        )

        toast(
            "در حال دانلود آپدیت..."
        )

        showLoading()

        lifecycleScope.launch {

            try {

                val apkFile =
                    withContext(
                        Dispatchers.IO
                    ) {

                        downloadUpdateApk(
                            result = result,
                            version = version
                        )
                    }

                if (
                    apkFile == null
                ) {

                    showUpdateStatus(
                        "دانلود آپدیت انجام نشد",
                        false
                    )

                    toastError(
                        "دانلود آپدیت انجام نشد"
                    )

                    return@launch
                }

                showUpdateStatus(
                    "در حال بررسی امنیت و امضای APK...",
                    true
                )

                /*
                 * Before sending anything to Android's
                 * package installer, verify that:
                 *
                 * 1. It is a real APK.
                 * 2. Package ID is MojAzad.
                 * 3. Release APK is signed with the
                 *    same production signing key.
                 */
                val verificationError =
                    withContext(
                        Dispatchers.IO
                    ) {

                        verifyDownloadedApk(
                            apkFile
                        )
                    }

                if (
                    verificationError != null
                ) {

                    apkFile.delete()

                    LogUtil.e(
                        AppConfig.TAG,
                        "Update APK verification failed: $verificationError"
                    )

                    showUpdateStatus(
                        "بررسی امنیت APK ناموفق بود",
                        false
                    )

                    toastError(
                        verificationError
                    )

                    return@launch
                }

                binding.progressUpdate.isIndeterminate =
                    false

                binding.progressUpdate.progress =
                    100

                showUpdateStatus(
                    "دانلود کامل شد — آماده نصب",
                    false
                )

                toast(
                    "دانلود کامل شد"
                )

                installDownloadedApk(
                    apkFile
                )

            } catch (
                e: Exception
            ) {

                LogUtil.e(
                    AppConfig.TAG,
                    "MojAzad update download failed",
                    e
                )

                showUpdateStatus(
                    "دانلود آپدیت انجام نشد",
                    false
                )

                toastError(
                    e.message
                        ?: "دانلود آپدیت انجام نشد"
                )

            } finally {

                hideLoading()

                setUpdateOperationRunning(
                    false
                )
            }
        }
    }

    /**
     * Downloads the APK to a temporary file first.
     *
     * Direct connection is attempted first.
     * If that fails, MojAzad retries using its
     * local HTTP proxy.
     */
    private fun downloadUpdateApk(
        result: CheckUpdateResult,
        version: String
    ): File? {

        val downloadUrl =
            result.downloadUrl
                ?.trim()
                .orEmpty()

        if (
            downloadUrl.isBlank()
        ) {

            return null
        }

        val updateDirectory =
            File(
                cacheDir,
                UPDATE_CACHE_DIR
            )

        if (
            !updateDirectory.exists() &&
            !updateDirectory.mkdirs()
        ) {

            throw IllegalStateException(
                "ساخت پوشه آپدیت انجام نشد"
            )
        }

        /*
         * Remove old temporary/update APKs.
         */
        updateDirectory
            .listFiles()
            ?.forEach {

                runCatching {
                    it.delete()
                }
            }

        val requestedName =
            result.downloadFileName
                ?.trim()
                .orEmpty()

        val safeFileName =
            when {

                requestedName.isNotBlank() &&
                    requestedName.endsWith(
                        ".apk",
                        ignoreCase = true
                    ) -> {

                    File(
                        requestedName
                    ).name
                }

                else -> {

                    val safeVersion =
                        result.latestVersion
                            ?.replace(
                                Regex(
                                    """[^A-Za-z0-9._-]"""
                                ),
                                "_"
                            )
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: "update"

                    "MojAzad-$safeVersion.apk"
                }
            }

        val finalFile =
            File(
                updateDirectory,
                safeFileName
            )

        val temporaryFile =
            File(
                updateDirectory,
                "$safeFileName.part"
            )

        temporaryFile.delete()
        finalFile.delete()

        val progressCallback:
            (
                downloadedBytes: Long,
                totalBytes: Long,
                progressPercent: Int?
            ) -> Unit =
            {
                    downloadedBytes,
                    totalBytes,
                    progressPercent ->

                runOnUiThread {

                    showDownloadProgress(
                        version =
                            version,
                        downloadedBytes =
                            downloadedBytes,
                        totalBytes =
                            totalBytes,
                        progressPercent =
                            progressPercent
                    )
                }
            }

        /*
         * First attempt:
         * normal network path.
         */
        var downloaded =
            HttpUtil.downloadToFile(
                request =
                    UrlContentRequest(
                        url =
                            downloadUrl,
                        timeout =
                            APK_DOWNLOAD_TIMEOUT_MS
                    ),
                targetFile =
                    temporaryFile,
                onProgress =
                    progressCallback
            )

        /*
         * Second attempt:
         * MojAzad HTTP proxy.
         *
         * Useful when GitHub itself is blocked
         * on the user's normal connection.
         */
        if (
            !downloaded
        ) {

            temporaryFile.delete()

            runOnUiThread {

                showUpdateStatus(
                    "اتصال مستقیم ناموفق بود؛ تلاش دوباره از طریق MojAzad...",
                    true
                )
            }

            val httpPort =
                SettingsManager
                    .getHttpPort()

            val proxyUsername =
                SettingsManager
                    .getSocksUsername()

            val proxyPassword =
                SettingsManager
                    .getSocksPassword()

            downloaded =
                HttpUtil.downloadToFile(
                    request =
                        UrlContentRequest(
                            url =
                                downloadUrl,
                            timeout =
                                APK_DOWNLOAD_TIMEOUT_MS,
                            httpPort =
                                httpPort,
                            proxyUsername =
                                proxyUsername,
                            proxyPassword =
                                proxyPassword
                        ),
                    targetFile =
                        temporaryFile,
                    onProgress =
                        progressCallback
                )
        }

        if (
            !downloaded ||
            !temporaryFile.exists() ||
            temporaryFile.length() <=
            0L
        ) {

            temporaryFile.delete()

            return null
        }

        if (
            finalFile.exists()
        ) {

            finalFile.delete()
        }

        val renamed =
            temporaryFile.renameTo(
                finalFile
            )

        if (
            !renamed
        ) {

            temporaryFile.copyTo(
                finalFile,
                overwrite = true
            )

            temporaryFile.delete()
        }

        if (
            !finalFile.exists() ||
            finalFile.length() <=
            0L
        ) {

            finalFile.delete()

            return null
        }

        LogUtil.i(
            AppConfig.TAG,
            "MojAzad update downloaded: ${finalFile.name}, ${finalFile.length()} bytes"
        )

        return finalFile
    }

    /**
     * Verifies that the downloaded APK really belongs
     * to MojAzad.
     *
     * Release build:
     * - APK must use MojAzad package ID.
     * - APK must have the same signing certificate.
     *
     * Debug build:
     * - Package ID is still checked.
     * - Signing comparison is skipped because the
     *   installed debug APK uses Android's debug key,
     *   while GitHub releases use MojAzad's permanent
     *   production signing key.
     */
    private fun verifyDownloadedApk(
        apkFile: File
    ): String? {

        val archiveInfo =
            getArchivePackageInfo(
                apkFile
            )
                ?: return "فایل دانلودشده APK معتبر نیست"

        if (
            archiveInfo.packageName !=
            BuildConfig.APPLICATION_ID
        ) {

            return "شناسه APK دانلودشده با MojAzad مطابقت ندارد"
        }

        if (
            BuildConfig.DEBUG
        ) {

            LogUtil.i(
                AppConfig.TAG,
                "Debug build: production signature comparison skipped"
            )

            return null
        }

        val installedInfo =
            getInstalledPackageInfo()
                ?: return "امضای نسخه نصب‌شده قابل بررسی نیست"

        val installedSignatures =
            getSigningDigests(
                installedInfo
            )

        val downloadedSignatures =
            getSigningDigests(
                archiveInfo
            )

        if (
            installedSignatures.isEmpty() ||
            downloadedSignatures.isEmpty()
        ) {

            return "امضای APK قابل بررسی نیست"
        }

        if (
            installedSignatures
                .intersect(
                    downloadedSignatures
                )
                .isEmpty()
        ) {

            return "امضای APK جدید با نسخه نصب‌شده MojAzad مطابقت ندارد"
        }

        return null
    }

    private fun getArchivePackageInfo(
        apkFile: File
    ): PackageInfo? {

        return try {

            @Suppress("DEPRECATION")
            packageManager
                .getPackageArchiveInfo(
                    apkFile.absolutePath,
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.P
                    ) {

                        PackageManager
                            .GET_SIGNING_CERTIFICATES

                    } else {

                        PackageManager
                            .GET_SIGNATURES
                    }
                )

        } catch (
            e: Exception
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "Failed to read update APK",
                e
            )

            null
        }
    }

    private fun getInstalledPackageInfo():
        PackageInfo? {

        return try {

            @Suppress("DEPRECATION")
            packageManager
                .getPackageInfo(
                    packageName,
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.P
                    ) {

                        PackageManager
                            .GET_SIGNING_CERTIFICATES

                    } else {

                        PackageManager
                            .GET_SIGNATURES
                    }
                )

        } catch (
            e: Exception
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "Failed to read installed MojAzad package info",
                e
            )

            null
        }
    }

    /**
     * Converts signing certificates to SHA-256
     * digests so they can be compared safely.
     */
    private fun getSigningDigests(
        packageInfo: PackageInfo
    ): Set<String> {

        val signatures =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {

                packageInfo
                    .signingInfo
                    ?.apkContentsSigners
                    ?.toList()
                    .orEmpty()

            } else {

                @Suppress("DEPRECATION")
                packageInfo
                    .signatures
                    ?.toList()
                    .orEmpty()
            }

        return signatures
            .map { signature ->

                val digest =
                    MessageDigest
                        .getInstance(
                            "SHA-256"
                        )
                        .digest(
                            signature
                                .toByteArray()
                        )

                digest.joinToString(
                    separator = ""
                ) {

                    "%02x".format(
                        it
                    )
                }
            }
            .toSet()
    }

    /**
     * Starts the Android installation flow.
     *
     * Android 8+ requires the user to explicitly
     * allow MojAzad as an APK installation source.
     */
    private fun installDownloadedApk(
        apkFile: File
    ) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O &&
            !packageManager
                .canRequestPackageInstalls()
        ) {

            pendingInstallFile =
                apkFile

            showUpdateStatus(
                "برای ادامه، اجازه نصب برنامه از MojAzad را فعال کنید",
                false
            )

            try {

                val settingsIntent =
                    Intent(
                        Settings
                            .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse(
                            "package:$packageName"
                        )
                    )

                requestInstallPermission
                    .launch(
                        settingsIntent
                    )

            } catch (
                e: Exception
            ) {

                LogUtil.e(
                    AppConfig.TAG,
                    "Failed to open unknown-app-source settings",
                    e
                )

                pendingInstallFile =
                    null

                showUpdateStatus(
                    "باز کردن تنظیمات نصب امکان‌پذیر نیست",
                    false
                )

                toastError(
                    "باز کردن تنظیمات نصب برنامه امکان‌پذیر نیست"
                )
            }

            return
        }

        openAndroidInstaller(
            apkFile
        )
    }

    /**
     * Opens the official Android package installer.
     *
     * The APK remains private inside MojAzad's cache
     * and is shared temporarily through FileProvider.
     */
    private fun openAndroidInstaller(
        apkFile: File
    ) {

        try {

            val apkUri =
                FileProvider.getUriForFile(
                    this,
                    "${BuildConfig.APPLICATION_ID}.cache",
                    apkFile
                )

            val installIntent =
                Intent(
                    Intent.ACTION_VIEW
                ).apply {

                    setDataAndType(
                        apkUri,
                        APK_MIME_TYPE
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    clipData =
                        ClipData.newRawUri(
                            "MojAzad Update",
                            apkUri
                        )
                }

            showUpdateStatus(
                "نصب‌کننده اندروید باز شد",
                false
            )

            startActivity(
                installIntent
            )

        } catch (
            e: ActivityNotFoundException
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "No Android APK installer found",
                e
            )

            showUpdateStatus(
                "نصب‌کننده APK در دستگاه پیدا نشد",
                false
            )

            toastError(
                "نصب‌کننده APK در دستگاه پیدا نشد"
            )

        } catch (
            e: Exception
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "Failed to open Android installer",
                e
            )

            showUpdateStatus(
                "باز کردن نصب‌کننده انجام نشد",
                false
            )

            toastError(
                e.message
                    ?: "باز کردن نصب‌کننده انجام نشد"
            )
        }
    }
}
