package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity :
    HelperBaseActivity(),
    NavigationView.OnNavigationItemSelectedListener {

    companion object {

        /*
         * MojAzad V3 Auto Failover
         */
        private const val AUTO_FAILOVER_PREFS =
            "mojazad_v3_preferences"

        private const val AUTO_FAILOVER_ENABLED =
            "auto_failover_enabled"

        private const val AUTO_FAILOVER_COOLDOWN_MS =
            60_000L

        private const val AUTO_FAILOVER_FIRST_CHECK_MS =
            2_500L

        private const val AUTO_FAILOVER_SECOND_CHECK_MS =
            2_500L

        private const val AUTO_FAILOVER_TIMEOUT_MS =
            45_000L

        /*
         * MojAzad V3 Dashboard
         */
        private const val DASHBOARD_REFRESH_INTERVAL_MS =
            1_000L

        private const val DASHBOARD_AUTO_PING_DELAY_MS =
            1_200L

        /*
         * Server Health
         */
        private const val HEALTH_EXCELLENT_MAX =
            200L

        private const val HEALTH_GOOD_MAX =
            400L

        private const val HEALTH_COLOR_EXCELLENT =
            "#00A86B"

        private const val HEALTH_COLOR_GOOD =
            "#0878E8"

        private const val HEALTH_COLOR_WEAK =
            "#F59E0B"

        private const val HEALTH_COLOR_OFFLINE =
            "#E53935"
    }

    private val binding by lazy {
        ActivityMainBinding.inflate(
            layoutInflater
        )
    }

    val mainViewModel: MainViewModel by viewModels()

    private lateinit var groupPagerAdapter:
        GroupPagerAdapter

    private var tabMediator:
        TabLayoutMediator? = null

    /*
     * MojAzad V3 Auto Failover state
     */
    private val autoFailoverPreferences by lazy {

        getSharedPreferences(
            AUTO_FAILOVER_PREFS,
            MODE_PRIVATE
        )
    }

    private var wasVpnRunning =
        false

    private var userRequestedStop =
        false

    private var restartInProgress =
        false

    private var autoFailoverInProgress =
        false

    private var lastAutoFailoverAt =
        0L

    /*
     * MojAzad V3 Dashboard state
     */
    private var dashboardJob:
        Job? = null

    private var dashboardAutoPingJob:
        Job? = null

    private var dashboardConnectedAtElapsed =
        0L

    private var dashboardSessionActive =
        false

    private val requestVpnPermission =
        registerForActivityResult(
            ActivityResultContracts
                .StartActivityForResult()
        ) {

            if (
                it.resultCode ==
                RESULT_OK
            ) {

                startV2Ray()
            }
        }

    private val requestActivityLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .StartActivityForResult()
        ) {

            if (
                SettingsChangeManager
                    .consumeRestartService() &&
                mainViewModel
                    .isRunning
                    .value == true
            ) {

                restartV2Ray()
            }

            if (
                SettingsChangeManager
                    .consumeSetupGroupTab()
            ) {

                setupGroupTab()
            }
        }

    private val requestSubSettingLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .StartActivityForResult()
        ) { result ->

            val needsSetupGroupTab =
                SettingsChangeManager
                    .consumeSetupGroupTab()

            if (
                SettingsChangeManager
                    .consumeRestartService() &&
                mainViewModel
                    .isRunning
                    .value == true
            ) {

                restartV2Ray()
            }

            if (
                result.resultCode ==
                RESULT_OK
            ) {

                val data =
                    result.data

                val subId =
                    data
                        ?.getStringExtra(
                            SubSettingActivity
                                .EXTRA_SUB_ID
                        )
                        .orEmpty()

                val isNewSubscription =
                    data
                        ?.getBooleanExtra(
                            SubSettingActivity
                                .EXTRA_IS_NEW_SUB,
                            false
                        )
                        ?: false

                if (
                    isNewSubscription &&
                    subId.isNotBlank()
                ) {

                    handleNewMojAzadSubscription(
                        subId
                    )

                    return@registerForActivityResult
                }
            }

            if (
                needsSetupGroupTab
            ) {

                setupGroupTab()

                refreshGroupTabTitles(
                    true
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            binding.root
        )

        setupToolbar(
            binding.toolbar,
            false,
            getString(
                R.string.title_server
            )
        )

        groupPagerAdapter =
            GroupPagerAdapter(
                this,
                emptyList()
            )

        binding.viewPager.adapter =
            groupPagerAdapter

        binding.viewPager.isUserInputEnabled =
            true

        setupNavigationDrawer()

        setupAutoFailoverSwitch()

        resetDashboard()

        binding.fab.setOnClickListener {

            handleFabAction()
        }

        /*
         * MojAzad V3:
         *
         * Tapping the dashboard only refreshes
         * current-server Ping + Country/IP.
         */
        binding.layoutTest.setOnClickListener {

            handleLayoutTestClick()
        }

        setupGroupTab()

        setupViewModel()

        val hasValidSubscription =
            MmkvManager
                .decodeSubscriptions()
                .any {

                    it.subscription.url
                        .isNotBlank()
                }

        if (
            !hasValidSubscription
        ) {

            showMojAzadActivationDialog()

        } else {

            refreshMojAzadSubscription()
        }

        checkAndRequestPermission(
            PermissionType.POST_NOTIFICATIONS
        ) {
        }
    }

    /*
     * =========================================================
     * MojAzad V3 Dashboard
     * =========================================================
     */

    private fun handleDashboardConnectionState(
        isRunning: Boolean
    ) {

        if (
            isRunning
        ) {

            if (
                !dashboardSessionActive
            ) {

                startDashboardSession()

            } else {

                updateDashboardServerIdentity()

                startDashboardTicker()
            }

        } else {

            stopDashboardSession()
        }
    }

    private fun startDashboardSession() {

        dashboardSessionActive =
            true

        dashboardConnectedAtElapsed =
            SystemClock.elapsedRealtime()

        updateDashboardServerIdentity()

        binding.tvTestState.text =
            "در حال دریافت کشور و IP..."

        /*
         * Traffic starts from zero in
         * NotificationManager when a new server
         * connection starts.
         */
        updateDashboardTrafficAndDuration()

        startDashboardTicker()

        /*
         * Automatically Ping current connection.
         *
         * User no longer has to tap the dashboard
         * after connecting.
         */
        dashboardAutoPingJob
            ?.cancel()

        dashboardAutoPingJob =
            lifecycleScope.launch {

                delay(
                    DASHBOARD_AUTO_PING_DELAY_MS
                )

                if (
                    mainViewModel
                        .isRunning
                        .value == true
                ) {

                    mainViewModel
                        .testCurrentServerRealPing()
                }
            }
    }

    private fun stopDashboardSession() {

        dashboardSessionActive =
            false

        dashboardConnectedAtElapsed =
            0L

        dashboardJob
            ?.cancel()

        dashboardJob =
            null

        dashboardAutoPingJob
            ?.cancel()

        dashboardAutoPingJob =
            null

        resetDashboard()
    }

    private fun startDashboardTicker() {

        if (
            dashboardJob?.isActive == true
        ) {

            return
        }

        dashboardJob =
            lifecycleScope.launch {

                while (
                    mainViewModel
                        .isRunning
                        .value == true
                ) {

                    updateDashboardServerIdentity()

                    updateDashboardTrafficAndDuration()

                    delay(
                        DASHBOARD_REFRESH_INTERVAL_MS
                    )
                }
            }
    }

    private fun updateDashboardServerIdentity() {

        val runningName =
            CoreServiceManager
                .getRunningServerName()
                .trim()

        val selectedGuid =
            MmkvManager
                .getSelectServer()

        val profile =
            selectedGuid
                ?.let {

                    MmkvManager
                        .decodeServerConfig(
                            it
                        )
                }

        val serverName =
            when {

                runningName.isNotBlank() ->

                    runningName

                !profile
                    ?.remarks
                    .isNullOrBlank() ->

                    profile
                        ?.remarks
                        .orEmpty()

                else ->

                    "Connected"
            }

        binding.tvDashboardServer.text =
            serverName

        /*
         * Before the live current-server Ping arrives,
         * show the most recent server-list Ping.
         */
        if (
            binding.tvDashboardPing
                .text
                .toString() ==
            "-- ms"
        ) {

            val cachedDelay =
                selectedGuid
                    ?.let {

                        MmkvManager
                            .decodeServerAffiliationInfo(
                                it
                            )
                            ?.testDelayMillis
                    }
                    ?: 0L

            if (
                cachedDelay != 0L
            ) {

                updateDashboardHealth(
                    cachedDelay
                )
            }
        }
    }

    private fun updateDashboardTrafficAndDuration() {

        if (
            mainViewModel
                .isRunning
                .value != true
        ) {

            return
        }

        val snapshot =
            NotificationManager
                .getTrafficSnapshot()

        binding.tvDashboardDownload.text =
            "↓ ${
                formatTrafficBytes(
                    snapshot.downloadBytes
                )
            }"

        binding.tvDashboardUpload.text =
            "↑ ${
                formatTrafficBytes(
                    snapshot.uploadBytes
                )
            }"

        val elapsedMillis =
            if (
                dashboardConnectedAtElapsed >
                0L
            ) {

                SystemClock.elapsedRealtime() -
                    dashboardConnectedAtElapsed

            } else {

                0L
            }

        binding.tvDashboardDuration.text =
            "⏱ ${
                formatConnectionDuration(
                    elapsedMillis
                )
            }"
    }

    private fun formatTrafficBytes(
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

    private fun formatConnectionDuration(
        elapsedMillis: Long
    ): String {

        val totalSeconds =
            (
                elapsedMillis /
                    1000L
                )
                .coerceAtLeast(
                    0L
                )

        val hours =
            totalSeconds /
                3600L

        val minutes =
            (
                totalSeconds %
                    3600L
                ) /
                60L

        val seconds =
            totalSeconds %
                60L

        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }

    private fun resetDashboard() {

        binding.tvDashboardServer.text =
            "Disconnected"

        binding.tvDashboardPing.text =
            "-- ms"

        binding.ivDashboardHealth.visibility =
            View.GONE

        binding.tvTestState.text =
            getString(
                R.string.connection_not_connected
            )

        binding.tvDashboardDownload.text =
            "↓ 0 B"

        binding.tvDashboardUpload.text =
            "↑ 0 B"

        binding.tvDashboardDuration.text =
            "⏱ 00:00:00"
    }

    /*
     * Handles result returned by:
     *
     * CoreServiceManager.measureV2rayDelay()
     *
     * Example:
     *
     * Success: Connection took 168ms
     * (DE) 2a01:4f8:....
     */
    private fun handleDashboardPingResult(
        content: String?
    ) {

        if (
            mainViewModel
                .isRunning
                .value != true
        ) {

            return
        }

        val result =
            content
                ?.trim()
                .orEmpty()

        if (
            result.isBlank()
        ) {

            return
        }

        val pingMatch =
            Regex(
                "(-?\\d+)\\s*ms",
                RegexOption.IGNORE_CASE
            )
                .find(
                    result
                )

        val delay =
            pingMatch
                ?.groupValues
                ?.getOrNull(
                    1
                )
                ?.toLongOrNull()

        if (
            delay != null
        ) {

            updateDashboardHealth(
                delay
            )
        }

        /*
         * Remote IP info arrives after the first line.
         *
         * We intentionally do not put
         * "Success: Connection took..."
         * here because Ping already has its own field.
         */
        val lines =
            result
                .lines()
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }

        if (
            lines.size >
            1
        ) {

            val remoteInfo =
                lines
                    .drop(
                        1
                    )
                    .joinToString(
                        " • "
                    )

            if (
                remoteInfo.isNotBlank()
            ) {

                binding.tvTestState.text =
                    remoteInfo
            }

        } else if (
            delay == null &&
            (
                result.contains(
                    "error",
                    ignoreCase = true
                ) ||
                result.contains(
                    "fail",
                    ignoreCase = true
                )
            )
        ) {

            binding.tvDashboardPing.text =
                "-1 ms"

            updateDashboardHealth(
                -1L
            )
        }
    }

    private fun updateDashboardHealth(
        delay: Long
    ) {

        binding.tvDashboardPing.text =
            "$delay ms"

        binding.ivDashboardHealth.visibility =
            View.VISIBLE

        when {

            delay <
                0L -> {

                binding.ivDashboardHealth
                    .setImageResource(
                        R.drawable.ic_health_offline
                    )

                binding.ivDashboardHealth
                    .imageTintList =
                    ColorStateList.valueOf(
                        Color.parseColor(
                            HEALTH_COLOR_OFFLINE
                        )
                    )

                binding.ivDashboardHealth
                    .contentDescription =
                    "Offline"
            }

            delay <=
                HEALTH_EXCELLENT_MAX -> {

                binding.ivDashboardHealth
                    .setImageResource(
                        R.drawable.ic_health_excellent
                    )

                binding.ivDashboardHealth
                    .imageTintList =
                    ColorStateList.valueOf(
                        Color.parseColor(
                            HEALTH_COLOR_EXCELLENT
                        )
                    )

                binding.ivDashboardHealth
                    .contentDescription =
                    "Excellent"
            }

            delay <=
                HEALTH_GOOD_MAX -> {

                binding.ivDashboardHealth
                    .setImageResource(
                        R.drawable.ic_health_good
                    )

                binding.ivDashboardHealth
                    .imageTintList =
                    ColorStateList.valueOf(
                        Color.parseColor(
                            HEALTH_COLOR_GOOD
                        )
                    )

                binding.ivDashboardHealth
                    .contentDescription =
                    "Good"
            }

            else -> {

                binding.ivDashboardHealth
                    .setImageResource(
                        R.drawable.ic_health_weak
                    )

                binding.ivDashboardHealth
                    .imageTintList =
                    ColorStateList.valueOf(
                        Color.parseColor(
                            HEALTH_COLOR_WEAK
                        )
                    )

                binding.ivDashboardHealth
                    .contentDescription =
                    "Weak"
            }
        }
    }

    /*
     * =========================================================
     * MojAzad V3 Auto Failover
     * =========================================================
     */

    private fun setupAutoFailoverSwitch() {

        binding.switchAutoFailover.isChecked =
            isAutoFailoverEnabled()

        binding.switchAutoFailover
            .setOnCheckedChangeListener {
                    _,
                    isChecked ->

                autoFailoverPreferences
                    .edit()
                    .putBoolean(
                        AUTO_FAILOVER_ENABLED,
                        isChecked
                    )
                    .apply()

                if (
                    isChecked
                ) {

                    toast(
                        "Auto Failover فعال شد"
                    )

                } else {

                    toast(
                        "Auto Failover غیرفعال شد"
                    )
                }
            }
    }

    private fun isAutoFailoverEnabled():
        Boolean {

        return autoFailoverPreferences
            .getBoolean(
                AUTO_FAILOVER_ENABLED,
                false
            )
    }

    private fun handleAutoFailoverRunningState(
        isRunning: Boolean
    ) {

        if (
            isRunning
        ) {

            wasVpnRunning =
                true

            autoFailoverInProgress =
                false

            userRequestedStop =
                false

            return
        }

        if (
            !wasVpnRunning
        ) {

            return
        }

        wasVpnRunning =
            false

        if (
            userRequestedStop
        ) {

            userRequestedStop =
                false

            return
        }

        if (
            restartInProgress
        ) {

            return
        }

        if (
            !isAutoFailoverEnabled()
        ) {

            return
        }

        if (
            autoFailoverInProgress
        ) {

            return
        }

        val now =
            System.currentTimeMillis()

        if (
            now -
                lastAutoFailoverAt <
            AUTO_FAILOVER_COOLDOWN_MS
        ) {

            return
        }

        startAutoFailover()
    }

    private fun startAutoFailover() {

        if (
            !isAutoFailoverEnabled()
        ) {

            return
        }

        autoFailoverInProgress =
            true

        lastAutoFailoverAt =
            System.currentTimeMillis()

        lifecycleScope.launch {

            delay(
                AUTO_FAILOVER_FIRST_CHECK_MS
            )

            if (
                !isAutoFailoverEnabled() ||
                mainViewModel
                    .isRunning
                    .value == true
            ) {

                autoFailoverInProgress =
                    false

                return@launch
            }

            delay(
                AUTO_FAILOVER_SECOND_CHECK_MS
            )

            if (
                !isAutoFailoverEnabled() ||
                mainViewModel
                    .isRunning
                    .value == true
            ) {

                autoFailoverInProgress =
                    false

                return@launch
            }

            toast(
                "در حال انتخاب سرور جایگزین..."
            )

            mainViewModel
                .testAllRealPing(
                    autoConnectAfterFinish = true
                )

            delay(
                AUTO_FAILOVER_TIMEOUT_MS
            )

            if (
                mainViewModel
                    .isRunning
                    .value != true
            ) {

                autoFailoverInProgress =
                    false
            }
        }
    }

    private fun handleNewMojAzadSubscription(
        subId: String
    ) {

        showLoading()

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                AngConfigManager
                    .updateConfigViaSubAll()

                val hasServers =
                    MmkvManager
                        .decodeServerList(
                            subId
                        )
                        .isNotEmpty()

                withContext(
                    Dispatchers.Main
                ) {

                    mainViewModel
                        .subscriptionIdChanged(
                            subId
                        )

                    setupGroupTab()

                    refreshGroupTabTitles(
                        true
                    )

                    if (
                        hasServers
                    ) {

                        mainViewModel
                            .testAllRealPing(
                                autoConnectAfterFinish = true
                            )

                        toast(
                            "اشتراک جدید با موفقیت اضافه شد"
                        )

                    } else {

                        toast(
                            "دریافت سرورهای اشتراک جدید انجام نشد"
                        )
                    }

                    hideLoading()
                }

            } catch (
                e: Exception
            ) {

                LogUtil.e(
                    AppConfig.TAG,
                    "MojAzad new subscription update failed",
                    e
                )

                withContext(
                    Dispatchers.Main
                ) {

                    mainViewModel
                        .subscriptionIdChanged(
                            subId
                        )

                    setupGroupTab()

                    refreshGroupTabTitles(
                        true
                    )

                    hideLoading()

                    toast(
                        "دریافت اشتراک جدید انجام نشد"
                    )
                }
            }
        }
    }

    private fun importMojAzadSubscriptionFromClipboard(
        subscriptionUrl: String
    ) {

        val normalizedUrl =
            subscriptionUrl
                .trim()

        val alreadyExists =
            MmkvManager
                .decodeSubscriptions()
                .any {

                    it.subscription.url
                        .trim() ==
                        normalizedUrl
                }

        if (
            alreadyExists
        ) {

            toast(
                "این اشتراک قبلاً اضافه شده است"
            )

            return
        }

        val existingSubscriptionCount =
            MmkvManager
                .decodeSubscriptions()
                .count {

                    it.subscription.url
                        .isNotBlank()
                }

        val subscriptionNumber =
            existingSubscriptionCount + 1

        val subId =
            Utils.getUuid()

        val subscription =
            SubscriptionItem().apply {

                remarks =
                    if (
                        subscriptionNumber <= 1
                    ) {

                        "MojAzad"

                    } else {

                        "MojAzad $subscriptionNumber"
                    }

                url =
                    normalizedUrl

                enabled =
                    true

                autoUpdate =
                    true

                updateInterval =
                    60L
            }

        MmkvManager.encodeSubscription(
            subId,
            subscription
        )

        SubscriptionUpdater.syncOne(
            subId = subId
        )

        handleNewMojAzadSubscription(
            subId
        )
    }

    private fun showMojAzadActivationDialog() {

        val input =
            AppCompatEditText(this).apply {

                hint =
                    "لینک اشتراک موج آزاد"

                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_URI

                setSingleLine(
                    true
                )
            }

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    "فعال‌سازی MojAzad"
                )
                .setMessage(
                    "لینک اشتراک خود را وارد کنید"
                )
                .setView(
                    input
                )
                .setCancelable(
                    false
                )
                .setPositiveButton(
                    "فعال‌سازی",
                    null
                )
                .create()

        dialog.setOnShowListener {

            dialog
                .getButton(
                    AlertDialog.BUTTON_POSITIVE
                )
                .setOnClickListener {

                    val subscriptionUrl =
                        input.text
                            ?.toString()
                            ?.trim()
                            .orEmpty()

                    if (
                        subscriptionUrl.isBlank()
                    ) {

                        input.error =
                            "لینک اشتراک را وارد کنید"

                        return@setOnClickListener
                    }

                    if (
                        !Utils.isValidUrl(
                            subscriptionUrl
                        )
                    ) {

                        input.error =
                            "لینک اشتراک معتبر نیست"

                        return@setOnClickListener
                    }

                    if (
                        !Utils.isValidSubUrl(
                            subscriptionUrl
                        )
                    ) {

                        input.error =
                            "لینک اشتراک معتبر نیست"

                        return@setOnClickListener
                    }

                    val subId =
                        Utils.getUuid()

                    val subscription =
                        SubscriptionItem().apply {

                            remarks =
                                "MojAzad"

                            url =
                                subscriptionUrl

                            enabled =
                                true

                            autoUpdate =
                                true

                            updateInterval =
                                60L
                        }

                    MmkvManager.encodeSubscription(
                        subId,
                        subscription
                    )

                    dialog.dismiss()

                    showLoading()

                    lifecycleScope.launch(
                        Dispatchers.IO
                    ) {

                        try {

                            val result =
                                AngConfigManager
                                    .updateConfigViaSubAll()

                            if (
                                result.successCount > 0
                            ) {

                                SubscriptionUpdater.syncOne(
                                    subId = subId
                                )

                                withContext(
                                    Dispatchers.Main
                                ) {

                                    mainViewModel
                                        .subscriptionIdChanged(
                                            subId
                                        )

                                    setupGroupTab()

                                    refreshGroupTabTitles(
                                        true
                                    )

                                    mainViewModel
                                        .testAllRealPing(
                                            autoConnectAfterFinish = true
                                        )

                                    hideLoading()

                                    toast(
                                        "اشتراک با موفقیت فعال شد"
                                    )
                                }

                            } else {

                                MmkvManager
                                    .removeSubscription(
                                        subId
                                    )

                                withContext(
                                    Dispatchers.Main
                                ) {

                                    hideLoading()

                                    toast(
                                        "دریافت اشتراک انجام نشد"
                                    )

                                    showMojAzadActivationDialog()
                                }
                            }

                        } catch (
                            e: Exception
                        ) {

                            MmkvManager
                                .removeSubscription(
                                    subId
                                )

                            LogUtil.e(
                                AppConfig.TAG,
                                "MojAzad subscription activation failed",
                                e
                            )

                            withContext(
                                Dispatchers.Main
                            ) {

                                hideLoading()

                                toast(
                                    "دریافت اشتراک انجام نشد"
                                )

                                showMojAzadActivationDialog()
                            }
                        }
                    }
                }
        }

        dialog.show()
    }

    private fun refreshMojAzadSubscription() {

        SubscriptionUpdater.sync()

        showLoading()

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                val result =
                    AngConfigManager
                        .updateConfigViaSubAll()

                withContext(
                    Dispatchers.Main
                ) {

                    if (
                        result.configCount > 0
                    ) {

                        setupGroupTab()

                        mainViewModel
                            .reloadServerList()

                        refreshGroupTabTitles(
                            true
                        )

                        mainViewModel
                            .testAllRealPing(
                                autoConnectAfterFinish = true
                            )

                    } else {

                        mainViewModel
                            .reloadServerList()
                    }

                    hideLoading()
                }

            } catch (
                e: Exception
            ) {

                LogUtil.e(
                    AppConfig.TAG,
                    "MojAzad startup subscription refresh failed",
                    e
                )

                withContext(
                    Dispatchers.Main
                ) {

                    mainViewModel
                        .reloadServerList()

                    hideLoading()
                }
            }
        }
    }

    private fun setupNavigationDrawer() {

        val toggle =
            ActionBarDrawerToggle(
                this,
                binding.drawerLayout,
                binding.toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close
            )

        binding.drawerLayout
            .addDrawerListener(
                toggle
            )

        toggle.syncState()

        binding.navView
            .setNavigationItemSelectedListener(
                this
            )

        onBackPressedDispatcher.addCallback(
            this,
            object :
                OnBackPressedCallback(
                    true
                ) {

                override fun handleOnBackPressed() {

                    if (
                        binding.drawerLayout
                            .isDrawerOpen(
                                GravityCompat.START
                            )
                    ) {

                        binding.drawerLayout
                            .closeDrawer(
                                GravityCompat.START
                            )

                    } else {

                        isEnabled =
                            false

                        onBackPressedDispatcher
                            .onBackPressed()

                        isEnabled =
                            true
                    }
                }
            }
        )
    }

    private fun setupViewModel() {

        mainViewModel
            .updateTestResultAction
            .observe(
                this
            ) { content ->

                handleDashboardPingResult(
                    content
                )
            }

        mainViewModel
            .isRunning
            .observe(
                this
            ) { isRunning ->

                applyRunningState(
                    false,
                    isRunning
                )

                handleDashboardConnectionState(
                    isRunning
                )

                handleAutoFailoverRunningState(
                    isRunning
                )
            }

        mainViewModel
            .autoConnectBestServerAction
            .observe(
                this
            ) { shouldConnect ->

                if (
                    shouldConnect != true
                ) {

                    return@observe
                }

                mainViewModel
                    .consumeAutoConnectBestServerAction()

                if (
                    autoFailoverInProgress &&
                    !isAutoFailoverEnabled()
                ) {

                    autoFailoverInProgress =
                        false

                    return@observe
                }

                if (
                    mainViewModel
                        .isRunning
                        .value == true
                ) {

                    restartV2Ray()

                } else if (
                    SettingsManager.isVpnMode()
                ) {

                    val intent =
                        VpnService.prepare(
                            this
                        )

                    if (
                        intent == null
                    ) {

                        startV2Ray()

                    } else {

                        requestVpnPermission
                            .launch(
                                intent
                            )
                    }

                } else {

                    startV2Ray()
                }
            }

        mainViewModel
            .startListenBroadcast()

        mainViewModel
            .initAssets(
                assets
            )
    }

    private fun setupGroupTab() {

        val groups =
            mainViewModel
                .getSubscriptions(
                    this
                )

        groupPagerAdapter
            .update(
                groups
            )

        tabMediator
            ?.detach()

        tabMediator =
            TabLayoutMediator(
                binding.tabGroup,
                binding.viewPager
            ) { tab, position ->

                groupPagerAdapter
                    .groups
                    .getOrNull(
                        position
                    )
                    ?.let {

                        tab.text =
                            it.remarks

                        tab.tag =
                            it.id
                    }

            }.also {

                it.attach()
            }

        val targetIndex =
            groups
                .indexOfFirst {

                    it.id ==
                        mainViewModel.subscriptionId
                }
                .takeIf {

                    it >= 0
                }
                ?: (
                    groups.size - 1
                )

        if (
            targetIndex >= 0
        ) {

            binding.viewPager
                .setCurrentItem(
                    targetIndex,
                    false
                )
        }

        binding.tabGroup.isVisible =
            groups.size > 1

        refreshGroupTabTitles(
            true
        )
    }

    fun refreshGroupTabTitles(
        refreshAll: Boolean = false
    ) {

        val groupsToRefresh =
            if (
                refreshAll ||
                mainViewModel
                    .subscriptionId
                    .isEmpty()
            ) {

                groupPagerAdapter
                    .groups

            } else {

                groupPagerAdapter
                    .groups
                    .filter {

                        it.id ==
                            mainViewModel
                                .subscriptionId
                    }
            }

        groupsToRefresh
            .forEach { group ->

                if (
                    group.id.isEmpty()
                ) {

                    return@forEach
                }

                val tabIndex =
                    groupPagerAdapter
                        .groups
                        .indexOfFirst {

                            it.id ==
                                group.id
                        }

                if (
                    tabIndex >= 0
                ) {

                    val count =
                        MmkvManager
                            .decodeServerList(
                                group.id
                            )
                            .size

                    binding.tabGroup
                        .getTabAt(
                            tabIndex
                        )
                        ?.text =
                        "${group.remarks} ($count)"
                }
            }
    }

    private fun handleFabAction() {

        val currentlyRunning =
            mainViewModel
                .isRunning
                .value == true

        if (
            currentlyRunning
        ) {

            userRequestedStop =
                true
        }

        applyRunningState(
            isLoading = true,
            isRunning = false
        )

        if (
            currentlyRunning
        ) {

            CoreServiceManager
                .stopVService(
                    this
                )

        } else if (
            SettingsManager.isVpnMode()
        ) {

            userRequestedStop =
                false

            val intent =
                VpnService.prepare(
                    this
                )

            if (
                intent == null
            ) {

                startV2Ray()

            } else {

                requestVpnPermission
                    .launch(
                        intent
                    )
            }

        } else {

            userRequestedStop =
                false

            startV2Ray()
        }
    }

    /*
     * MojAzad V3:
     *
     * Dashboard tap ONLY refreshes current Ping/IP.
     */
    private fun handleLayoutTestClick() {

        if (
            mainViewModel
                .isRunning
                .value == true
        ) {

            binding.tvDashboardPing.text =
                "..."

            mainViewModel
                .testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {

        if (
            MmkvManager
                .getSelectServer()
                .isNullOrEmpty()
        ) {

            toast(
                R.string.title_file_chooser
            )

            return
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.CINNAMON_BUN &&
            MmkvManager.decodeSettingsBool(
                AppConfig.PREF_PROXY_SHARING
            )
        ) {

            checkAndRequestPermission(
                PermissionType.ACCESS_LOCAL_NETWORK
            ) {
            }
        }

        CoreServiceManager
            .startVService(
                this
            )
    }

    fun restartV2Ray() {

        restartInProgress =
            true

        if (
            mainViewModel
                .isRunning
                .value == true
        ) {

            CoreServiceManager
                .stopVService(
                    this
                )
        }

        lifecycleScope.launch {

            delay(
                500
            )

            startV2Ray()

            delay(
                2_000
            )

            restartInProgress =
                false
        }
    }

    private fun setTestState(
        content: String?
    ) {

        binding.tvTestState.text =
            content
    }

    private fun applyRunningState(
        isLoading: Boolean,
        isRunning: Boolean
    ) {

        if (
            isLoading
        ) {

            binding.fab
                .setImageResource(
                    R.drawable.ic_fab_check
                )

            binding.fab
                .backgroundTintList =
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.md_theme_primary
                    )
                )

            binding.fab.contentDescription =
                "Connecting"

            return
        }

        if (
            isRunning
        ) {

            binding.fab
                .setImageResource(
                    R.drawable.ic_stop_24dp
                )

            binding.fab
                .backgroundTintList =
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.color_fab_active
                    )
                )

            binding.fab
                .contentDescription =
                getString(
                    R.string.action_stop_service
                )

            binding.layoutTest.isFocusable =
                true

        } else {

            binding.fab
                .setImageResource(
                    R.drawable.ic_play_24dp
                )

            binding.fab
                .backgroundTintList =
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.color_fab_inactive
                    )
                )

            binding.fab
                .contentDescription =
                getString(
                    R.string.tasker_start_service
                )

            binding.layoutTest.isFocusable =
                false
        }
    }

    override fun onResume() {

        super.onResume()

        if (
            mainViewModel
                .isRunning
                .value == true
        ) {

            updateDashboardServerIdentity()

            startDashboardTicker()
        }
    }

    override fun onPause() {

        dashboardJob
            ?.cancel()

        dashboardJob =
            null

        super.onPause()
    }

    override fun onCreateOptionsMenu(
        menu: Menu
    ): Boolean {

        menuInflater.inflate(
            R.menu.menu_main,
            menu
        )

        val searchItem =
            menu.findItem(
                R.id.search_view
            )

        if (
            searchItem != null
        ) {

            val searchView =
                searchItem.actionView
                    as SearchView

            searchView
                .setOnQueryTextListener(
                    object :
                        SearchView
                            .OnQueryTextListener {

                        override fun onQueryTextSubmit(
                            query: String?
                        ): Boolean =
                            false

                        override fun onQueryTextChange(
                            newText: String?
                        ): Boolean {

                            mainViewModel
                                .filterConfig(
                                    newText.orEmpty()
                                )

                            return false
                        }
                    }
                )

            searchView
                .setOnCloseListener {

                    mainViewModel
                        .filterConfig(
                            ""
                        )

                    false
                }
        }

        return super
            .onCreateOptionsMenu(
                menu
            )
    }

    override fun onOptionsItemSelected(
        item: MenuItem
    ) =
        when (
            item.itemId
        ) {

            R.id.import_qrcode -> {

                importQRcode()

                true
            }

            R.id.import_clipboard -> {

                importClipboard()

                true
            }

            R.id.import_local -> {

                importConfigLocal()

                true
            }

            R.id.import_manually_policy_group -> {

                importManually(
                    EConfigType
                        .POLICYGROUP
                        .value
                )

                true
            }

            R.id.import_manually_proxy_chain -> {

                importManually(
                    EConfigType
                        .PROXYCHAIN
                        .value
                )

                true
            }

            R.id.import_manually_vmess -> {

                importManually(
                    EConfigType.VMESS.value
                )

                true
            }

            R.id.import_manually_vless -> {

                importManually(
                    EConfigType.VLESS.value
                )

                true
            }

            R.id.import_manually_ss -> {

                importManually(
                    EConfigType
                        .SHADOWSOCKS
                        .value
                )

                true
            }

            R.id.import_manually_socks -> {

                importManually(
                    EConfigType.SOCKS.value
                )

                true
            }

            R.id.import_manually_http -> {

                importManually(
                    EConfigType.HTTP.value
                )

                true
            }

            R.id.import_manually_trojan -> {

                importManually(
                    EConfigType.TROJAN.value
                )

                true
            }

            R.id.import_manually_wireguard -> {

                importManually(
                    EConfigType
                        .WIREGUARD
                        .value
                )

                true
            }

            R.id.import_manually_hysteria2 -> {

                importManually(
                    EConfigType
                        .HYSTERIA2
                        .value
                )

                true
            }

            R.id.export_all -> {

                exportAll()

                true
            }

            R.id.real_ping_all -> {

                toast(
                    getString(
                        R.string
                            .connection_test_testing_count,
                        mainViewModel
                            .serversCache
                            .count()
                    )
                )

                mainViewModel
                    .testAllRealPing()

                true
            }

            R.id.service_restart -> {

                restartV2Ray()

                true
            }

            R.id.del_all_config -> {

                delAllConfig()

                true
            }

            R.id.del_duplicate_config -> {

                delDuplicateConfig()

                true
            }

            R.id.del_invalid_config -> {

                delInvalidConfig()

                true
            }

            R.id.sort_by_test_results -> {

                sortByTestResults()

                true
            }

            R.id.sub_update -> {

                importConfigViaSub()

                true
            }

            R.id.locate_selected_config -> {

                locateSelectedServer()

                true
            }

            else ->

                super.onOptionsItemSelected(
                    item
                )
        }

    private fun importManually(
        createConfigType: Int
    ) {

        if (
            createConfigType ==
            EConfigType.POLICYGROUP.value
        ) {

            startActivity(
                Intent()
                    .putExtra(
                        "subscriptionId",
                        mainViewModel
                            .subscriptionId
                    )
                    .setClass(
                        this,
                        ServerGroupActivity::class.java
                    )
            )

        } else if (
            createConfigType ==
            EConfigType.PROXYCHAIN.value
        ) {

            startActivity(
                Intent()
                    .putExtra(
                        "subscriptionId",
                        mainViewModel
                            .subscriptionId
                    )
                    .setClass(
                        this,
                        ServerProxyChainActivity::class.java
                    )
            )

        } else {

            startActivity(
                Intent()
                    .putExtra(
                        "createConfigType",
                        createConfigType
                    )
                    .putExtra(
                        "subscriptionId",
                        mainViewModel
                            .subscriptionId
                    )
                    .setClass(
                        this,
                        ServerActivity::class.java
                    )
            )
        }
    }

    private fun importQRcode():
        Boolean {

        launchQRCodeScanner {
                scanResult ->

            if (
                scanResult != null
            ) {

                importBatchConfig(
                    scanResult
                )
            }
        }

        return true
    }

    private fun importClipboard():
        Boolean {

        try {

            val clipboard =
                Utils.getClipboard(
                    this
                )
                    ?.trim()
                    .orEmpty()

            if (
                clipboard.isBlank()
            ) {

                toast(
                    "کلیپ‌بورد خالی است"
                )

                return false
            }

            val isSingleHttpUrl =
                !clipboard.contains(
                    "\n"
                ) &&
                (
                    clipboard.startsWith(
                        "https://",
                        ignoreCase = true
                    ) ||
                    clipboard.startsWith(
                        "http://",
                        ignoreCase = true
                    )
                )

            if (
                isSingleHttpUrl
            ) {

                if (
                    !Utils.isValidUrl(
                        clipboard
                    )
                ) {

                    toast(
                        "لینک معتبر نیست"
                    )

                    return false
                }

                if (
                    !Utils.isValidSubUrl(
                        clipboard
                    )
                ) {

                    toast(
                        "لینک اشتراک معتبر نیست"
                    )

                    return false
                }

                importMojAzadSubscriptionFromClipboard(
                    clipboard
                )

            } else {

                importBatchConfig(
                    clipboard
                )
            }

        } catch (
            e: Exception
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "Failed to import config from clipboard",
                e
            )

            return false
        }

        return true
    }

    private fun importBatchConfig(
        server: String?
    ) {

        showLoading()

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                val (
                    count,
                    countSub
                ) =
                    AngConfigManager
                        .importBatchConfig(
                            server,
                            mainViewModel
                                .subscriptionId,
                            true
                        )

                delay(
                    500L
                )

                withContext(
                    Dispatchers.Main
                ) {

                    when {

                        count > 0 -> {

                            toast(
                                getString(
                                    R.string
                                        .title_import_config_count,
                                    count
                                )
                            )

                            mainViewModel
                                .reloadServerList()

                            refreshGroupTabTitles()
                        }

                        countSub > 0 -> {

                            setupGroupTab()
                        }

                        else -> {

                            toastError(
                                R.string.toast_failure
                            )
                        }
                    }

                    hideLoading()
                }

            } catch (
                e: Exception
            ) {

                withContext(
                    Dispatchers.Main
                ) {

                    toastError(
                        R.string.toast_failure
                    )

                    hideLoading()
                }

                LogUtil.e(
                    AppConfig.TAG,
                    "Failed to import batch config",
                    e
                )
            }
        }
    }

    private fun importConfigLocal():
        Boolean {

        try {

            showFileChooser()

        } catch (
            e: Exception
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "Failed to import config from local file",
                e
            )

            return false
        }

        return true
    }

    fun importConfigViaSub():
        Boolean {

        showLoading()

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            val result =
                mainViewModel
                    .updateConfigViaSubAll()

            delay(
                500L
            )

            launch(
                Dispatchers.Main
            ) {

                if (
                    result.successCount +
                    result.failureCount +
                    result.skipCount == 0
                ) {

                    toast(
                        R.string
                            .title_update_subscription_no_subscription
                    )

                } else if (
                    result.successCount > 0 &&
                    result.failureCount +
                    result.skipCount == 0
                ) {

                    toast(
                        getString(
                            R.string
                                .title_update_config_count,
                            result.configCount
                        )
                    )

                } else {

                    toast(
                        getString(
                            R.string
                                .title_update_subscription_result,
                            result.configCount,
                            result.successCount,
                            result.failureCount,
                            result.skipCount
                        )
                    )
                }

                if (
                    result.configCount > 0
                ) {

                    mainViewModel
                        .reloadServerList()

                    refreshGroupTabTitles()
                }

                hideLoading()
            }
        }

        return true
    }

    private fun exportAll() {

        showLoading()

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            val ret =
                mainViewModel
                    .exportAllServer()

            launch(
                Dispatchers.Main
            ) {

                if (
                    ret > 0
                ) {

                    toast(
                        getString(
                            R.string
                                .title_export_config_count,
                            ret
                        )
                    )

                } else {

                    toastError(
                        R.string.toast_failure
                    )
                }

                hideLoading()
            }
        }
    }

    private fun delAllConfig() {

        AlertDialog.Builder(this)
            .setMessage(
                R.string.del_config_comfirm
            )
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->

                showLoading()

                lifecycleScope.launch(
                    Dispatchers.IO
                ) {

                    val ret =
                        mainViewModel
                            .removeAllServer()

                    launch(
                        Dispatchers.Main
                    ) {

                        mainViewModel
                            .reloadServerList()

                        refreshGroupTabTitles()

                        toast(
                            getString(
                                R.string
                                    .title_del_config_count,
                                ret
                            )
                        )

                        hideLoading()
                    }
                }
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun delDuplicateConfig() {

        AlertDialog.Builder(this)
            .setMessage(
                R.string.del_config_comfirm
            )
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->

                showLoading()

                lifecycleScope.launch(
                    Dispatchers.IO
                ) {

                    val ret =
                        mainViewModel
                            .removeDuplicateServer()

                    launch(
                        Dispatchers.Main
                    ) {

                        mainViewModel
                            .reloadServerList()

                        refreshGroupTabTitles()

                        toast(
                            getString(
                                R.string
                                    .title_del_duplicate_config_count,
                                ret
                            )
                        )

                        hideLoading()
                    }
                }
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun delInvalidConfig() {

        AlertDialog.Builder(this)
            .setMessage(
                R.string
                    .del_invalid_config_comfirm
            )
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->

                showLoading()

                lifecycleScope.launch(
                    Dispatchers.IO
                ) {

                    val ret =
                        mainViewModel
                            .removeInvalidServer()

                    launch(
                        Dispatchers.Main
                    ) {

                        mainViewModel
                            .reloadServerList()

                        refreshGroupTabTitles()

                        toast(
                            getString(
                                R.string
                                    .title_del_config_count,
                                ret
                            )
                        )

                        hideLoading()
                    }
                }
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun sortByTestResults() {

        showLoading()

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            mainViewModel
                .sortByTestResults()

            launch(
                Dispatchers.Main
            ) {

                mainViewModel
                    .reloadServerList()

                hideLoading()
            }
        }
    }

    private fun showFileChooser() {

        launchFileChooser { uri ->

            if (
                uri == null
            ) {

                return@launchFileChooser
            }

            readContentFromUri(
                uri
            )
        }
    }

    private fun readContentFromUri(
        uri: Uri
    ) {

        try {

            contentResolver
                .openInputStream(
                    uri
                )
                .use { input ->

                    importBatchConfig(
                        input
                            ?.bufferedReader()
                            ?.readText()
                    )
                }

        } catch (
            e: Exception
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "Failed to read content from URI",
                e
            )
        }
    }

    private fun locateSelectedServer() {

        val targetSubscriptionId =
            mainViewModel
                .findSubscriptionIdBySelect()

        if (
            targetSubscriptionId
                .isNullOrEmpty()
        ) {

            toast(
                R.string.title_file_chooser
            )

            return
        }

        val targetGroupIndex =
            groupPagerAdapter
                .groups
                .indexOfFirst {

                    it.id ==
                        targetSubscriptionId
                }

        if (
            targetGroupIndex < 0
        ) {

            toast(
                R.string
                    .toast_server_not_found_in_group
            )

            return
        }

        if (
            binding.viewPager
                .currentItem !=
            targetGroupIndex
        ) {

            binding.viewPager
                .setCurrentItem(
                    targetGroupIndex,
                    true
                )

            binding.viewPager
                .postDelayed(
                    {

                        scrollToSelectedServer(
                            targetGroupIndex
                        )
                    },
                    1000
                )

        } else {

            scrollToSelectedServer(
                targetGroupIndex
            )
        }
    }

    private fun scrollToSelectedServer(
        groupIndex: Int
    ) {

        val itemId =
            groupPagerAdapter
                .getItemId(
                    groupIndex
                )

        val fragment =
            supportFragmentManager
                .findFragmentByTag(
                    "f$itemId"
                )
                as? GroupServerFragment

        if (
            fragment?.isAdded == true &&
            fragment.view != null
        ) {

            fragment
                .scrollToSelectedServer()

        } else {

            toast(
                R.string
                    .toast_fragment_not_available
            )
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent
    ): Boolean {

        if (
            keyCode ==
            KeyEvent.KEYCODE_BACK ||
            keyCode ==
            KeyEvent.KEYCODE_BUTTON_B
        ) {

            moveTaskToBack(
                false
            )

            return true
        }

        return super
            .onKeyDown(
                keyCode,
                event
            )
    }

    override fun onNavigationItemSelected(
        item: MenuItem
    ): Boolean {

        when (
            item.itemId
        ) {

            R.id.sub_setting ->

                requestSubSettingLauncher.launch(
                    Intent(
                        this,
                        SubSettingActivity::class.java
                    )
                )

            R.id.per_app_proxy_settings ->

                requestActivityLauncher.launch(
                    Intent(
                        this,
                        PerAppProxyActivity::class.java
                    )
                )

            R.id.routing_setting ->

                requestActivityLauncher.launch(
                    Intent(
                        this,
                        RoutingSettingActivity::class.java
                    )
                )

            R.id.user_asset_setting ->

                requestActivityLauncher.launch(
                    Intent(
                        this,
                        UserAssetActivity::class.java
                    )
                )

            R.id.settings ->

                requestActivityLauncher.launch(
                    Intent(
                        this,
                        SettingsActivity::class.java
                    )
                )

            R.id.promotion ->

                Utils.openUri(
                    this,
                    "${
                        Utils.decode(
                            AppConfig.APP_PROMOTION_URL
                        )
                    }?t=${
                        System.currentTimeMillis()
                    }"
                )

            R.id.logcat ->

                startActivity(
                    Intent(
                        this,
                        LogcatActivity::class.java
                    )
                )

            R.id.check_for_update ->

                startActivity(
                    Intent(
                        this,
                        CheckUpdateActivity::class.java
                    )
                )

            R.id.backup_restore ->

                requestActivityLauncher.launch(
                    Intent(
                        this,
                        BackupActivity::class.java
                    )
                )

            R.id.about ->

                startActivity(
                    Intent(
                        this,
                        AboutActivity::class.java
                    )
                )
        }

        binding.drawerLayout
            .closeDrawer(
                GravityCompat.START
            )

        return true
    }

    override fun onDestroy() {

        dashboardJob
            ?.cancel()

        dashboardAutoPingJob
            ?.cancel()

        tabMediator
            ?.detach()

        super.onDestroy()
    }
}
