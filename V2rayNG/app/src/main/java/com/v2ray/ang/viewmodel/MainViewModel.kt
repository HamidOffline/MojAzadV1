package com.v2ray.ang.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.regex.PatternSyntaxException

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {

        /*
         * =====================================================
         * MojAzad Initial Best Server Selection
         * =====================================================
         *
         * Quality information is written by CoreTestService.
         *
         * Stored format:
         *
         * v1|median|jitter|successCount|attemptCount|samples
         */
        private const val QUALITY_RESULT_KEY_PREFIX =
            "mojazad_initial_quality_"

        private const val QUALITY_RESULT_VERSION =
            "v1"

        /*
         * Jitter matters because a server with:
         *
         * 100 / 105 / 110
         *
         * is generally a better initial choice than:
         *
         * 70 / 220 / 350
         *
         * even if the second server once produced
         * a lower raw Ping.
         */
        private const val QUALITY_JITTER_WEIGHT =
            2L

        /*
         * Every failed Ping attempt adds this penalty.
         *
         * The server is NOT deleted.
         *
         * Example:
         *
         * 3/3 success = no failure penalty
         * 2/3 success = +250
         * 1/3 success = +500
         */
        private const val QUALITY_FAILURE_PENALTY =
            250L

        /*
         * Servers with very high latency remain valid,
         * but receive an additional penalty.
         */
        private const val QUALITY_HIGH_PING_THRESHOLD =
            400L

        /*
         * Additional penalty for every millisecond above
         * QUALITY_HIGH_PING_THRESHOLD.
         */
        private const val QUALITY_HIGH_PING_WEIGHT =
            1L
    }

    private var serverList =
        mutableListOf<String>()

    var subscriptionId: String =
        MmkvManager.decodeSettingsString(
            AppConfig.CACHE_SUBSCRIPTION_ID,
            ""
        ).orEmpty()

    var keywordFilter =
        ""

    val serversCache =
        mutableListOf<ServersCache>()

    val isRunning by lazy {
        MutableLiveData<Boolean>()
    }

    val updateListAction by lazy {
        MutableLiveData<Int>()
    }

    val updateTestResultAction by lazy {
        MutableLiveData<String>()
    }

    /*
     * MojAzad:
     *
     * MainActivity listens to this.
     *
     * true means:
     *
     * - Ping/quality test finished successfully
     * - best initial server was selected
     * - MojAzad can now connect
     */
    val autoConnectBestServerAction by lazy {
        MutableLiveData<Boolean>(
            false
        )
    }

    /*
     * Only automatic MojAzad startup/activation Ping
     * should trigger automatic connection.
     *
     * Manual Ping must not auto-connect.
     */
    private var connectBestServerAfterPing =
        false

    /**
     * One server's quality result for the current
     * Real Ping batch.
     */
    private data class ServerQualityResult(
        val guid: String,
        val medianMillis: Long,
        val jitterMillis: Long,
        val successCount: Int,
        val attemptCount: Int,
        val samples: List<Long>
    ) {

        val failureCount: Int
            get() =
                (
                    attemptCount -
                        successCount
                    )
                    .coerceAtLeast(
                        0
                    )

        val isUsable: Boolean
            get() =
                medianMillis > 0L &&
                    successCount > 0 &&
                    attemptCount > 0
    }

    /**
     * Candidate used for final initial-server selection.
     *
     * Lower score is better.
     */
    private data class ServerQualityCandidate(
        val quality: ServerQualityResult,
        val score: Long
    )

    /**
     * Start listening for service broadcasts.
     */
    fun startListenBroadcast() {

        isRunning.value =
            false

        val mFilter =
            IntentFilter(
                AppConfig.BROADCAST_ACTION_ACTIVITY
            )

        ContextCompat.registerReceiver(
            getApplication(),
            mMsgReceiver,
            mFilter,
            Utils.receiverFlags()
        )

        MessageUtil.sendMsg2Service(
            getApplication(),
            AppConfig.MSG_REGISTER_CLIENT,
            ""
        )
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {

        getApplication<AngApplication>()
            .unregisterReceiver(
                mMsgReceiver
            )

        LogUtil.i(
            AppConfig.TAG,
            "Main ViewModel is cleared"
        )

        super.onCleared()
    }

    /**
     * Reload server list.
     */
    fun reloadServerList() {

        serverList =
            if (
                subscriptionId.isEmpty()
            ) {

                MmkvManager
                    .decodeAllServerList()

            } else {

                MmkvManager
                    .decodeServerList(
                        subscriptionId
                    )
            }

        updateCache()

        updateListAction.value =
            -1
    }

    /**
     * Remove a server.
     */
    fun removeServer(
        guid: String
    ) {

        serverList.remove(
            guid
        )

        MmkvManager.removeServer(
            guid
        )

        val index =
            getPosition(
                guid
            )

        if (
            index >= 0
        ) {

            serversCache.removeAt(
                index
            )
        }
    }

    /**
     * Swap two servers.
     */
    fun swapServer(
        fromPosition: Int,
        toPosition: Int
    ) {

        if (
            subscriptionId.isEmpty()
        ) {

            return
        }

        Collections.swap(
            serverList,
            fromPosition,
            toPosition
        )

        Collections.swap(
            serversCache,
            fromPosition,
            toPosition
        )

        MmkvManager.encodeServerList(
            serverList,
            subscriptionId
        )
    }

    /**
     * Update server cache.
     */
    @Synchronized
    fun updateCache() {

        serversCache.clear()

        val kw =
            keywordFilter.trim()

        val searchRegex =
            try {

                if (
                    kw.isNotEmpty()
                ) {

                    Regex(
                        kw,
                        setOf(
                            RegexOption.IGNORE_CASE
                        )
                    )

                } else {

                    null
                }

            } catch (
                e: PatternSyntaxException
            ) {

                null
            }

        for (
            guid in serverList
        ) {

            val profile =
                MmkvManager
                    .decodeServerConfig(
                        guid
                    )
                    ?: continue

            if (
                kw.isEmpty()
            ) {

                serversCache.add(
                    ServersCache(
                        guid,
                        profile
                    )
                )

                continue
            }

            val remarks =
                profile.remarks

            val description =
                profile.description.orEmpty()

            val server =
                profile.server.orEmpty()

            val protocol =
                profile.configType.name

            if (
                remarks.matchesPattern(
                    searchRegex,
                    kw
                ) ||
                description.matchesPattern(
                    searchRegex,
                    kw
                ) ||
                server.matchesPattern(
                    searchRegex,
                    kw
                ) ||
                protocol.matchesPattern(
                    searchRegex,
                    kw
                )
            ) {

                serversCache.add(
                    ServersCache(
                        guid,
                        profile
                    )
                )
            }
        }
    }

    /**
     * Update configuration via subscription.
     */
    fun updateConfigViaSubAll():
        SubscriptionUpdateResult {

        return if (
            subscriptionId.isEmpty()
        ) {

            AngConfigManager
                .updateConfigViaSubAll()

        } else {

            val subItem =
                MmkvManager
                    .decodeSubscription(
                        subscriptionId
                    )
                    ?: return SubscriptionUpdateResult()

            AngConfigManager
                .updateConfigViaSub(
                    SubscriptionCache(
                        subscriptionId,
                        subItem
                    )
                )
        }
    }

    /**
     * Export servers.
     */
    fun exportAllServer():
        Int {

        val serverListCopy =
            if (
                subscriptionId.isEmpty() &&
                keywordFilter.isEmpty()
            ) {

                serverList

            } else {

                serversCache
                    .map {
                        it.guid
                    }
                    .toList()
            }

        return AngConfigManager
            .shareNonCustomConfigsToClipboard(
                getApplication<AngApplication>(),
                serverListCopy
            )
    }

    /**
     * Test real ping for all currently loaded servers.
     *
     * autoConnectAfterFinish = false:
     * normal/manual Ping.
     *
     * autoConnectAfterFinish = true:
     * MojAzad automatic startup Ping.
     *
     * Automatic flow:
     *
     * Test ->
     * calculate quality ->
     * sort ->
     * select best-quality server ->
     * connect once.
     */
    fun testAllRealPing(
        autoConnectAfterFinish: Boolean = false
    ) {

        /*
         * Cancel any previous Ping batch.
         */
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(
                key =
                    AppConfig.MSG_MEASURE_CONFIG_CANCEL
            )
        )

        /*
         * Remember whether this batch should connect
         * after testing finishes.
         */
        connectBestServerAfterPing =
            autoConnectAfterFinish

        /*
         * Clear old visible Ping results.
         *
         * CoreTestService separately clears the quality
         * result for every server before the new batch.
         */
        MmkvManager
            .clearAllTestDelayResults(
                serversCache
                    .map {
                        it.guid
                    }
                    .toList()
            )

        updateListAction.value =
            -1

        viewModelScope.launch(
            Dispatchers.Default
        ) {

            if (
                serversCache.isEmpty()
            ) {

                connectBestServerAfterPing =
                    false

                return@launch
            }

            /*
             * Always send the exact currently loaded
             * server GUID list directly to CoreTestService.
             */
            MessageUtil.sendMsg2TestService(
                getApplication(),
                TestServiceMessage(
                    key =
                        AppConfig.MSG_MEASURE_CONFIG_START,

                    subscriptionId =
                        subscriptionId,

                    serverGuids =
                        serversCache
                            .map {
                                it.guid
                            }
                )
            )
        }
    }

    /**
     * Test current selected server.
     */
    fun testCurrentServerRealPing() {

        MessageUtil.sendMsg2Service(
            getApplication(),
            AppConfig.MSG_MEASURE_DELAY,
            ""
        )
    }

    /**
     * Change subscription.
     */
    fun subscriptionIdChanged(
        id: String
    ) {

        if (
            subscriptionId !=
            id
        ) {

            subscriptionId =
                id

            MmkvManager.encodeSettings(
                AppConfig.CACHE_SUBSCRIPTION_ID,
                subscriptionId
            )
        }

        reloadServerList()
    }

    /**
     * Get subscriptions.
     */
    fun getSubscriptions(
        context: Context
    ): List<GroupMapItem> {

        val subscriptions =
            MmkvManager
                .decodeSubscriptions()

        if (
            subscriptionId.isNotEmpty() &&
            !subscriptions
                .map {
                    it.guid
                }
                .contains(
                    subscriptionId
                )
        ) {

            subscriptionIdChanged(
                ""
            )
        }

        val groups =
            mutableListOf<GroupMapItem>()

        if (
            MmkvManager.decodeSettingsBool(
                AppConfig.PREF_GROUP_ALL_DISPLAY
            )
        ) {

            groups.add(
                GroupMapItem(
                    id =
                        "",

                    remarks =
                        context.getString(
                            R.string.filter_config_all
                        )
                )
            )
        }

        subscriptions.forEach { sub ->

            groups.add(
                GroupMapItem(
                    id =
                        sub.guid,

                    remarks =
                        sub.subscription.remarks
                )
            )
        }

        return groups
    }

    /**
     * Get server position by GUID.
     */
    fun getPosition(
        guid: String
    ): Int {

        serversCache
            .forEachIndexed {
                index,
                item ->

                if (
                    item.guid ==
                    guid
                ) {

                    return index
                }
            }

        return -1
    }

    /**
     * Remove duplicate servers.
     */
    fun removeDuplicateServer():
        Int {

        val serversCacheCopy =
            serversCache
                .toList()
                .toMutableList()

        val deleteServer =
            mutableListOf<String>()

        serversCacheCopy
            .forEachIndexed {
                index,
                sc ->

                val profile =
                    sc.profile

                if (
                    profile.configType
                        .isComplexType()
                ) {

                    return@forEachIndexed
                }

                serversCacheCopy
                    .forEachIndexed {
                        index2,
                        sc2 ->

                        if (
                            index2 >
                            index
                        ) {

                            val profile2 =
                                sc2.profile

                            if (
                                profile2.configType
                                    .isComplexType()
                            ) {

                                return@forEachIndexed
                            }

                            if (
                                profile ==
                                profile2 &&
                                !deleteServer.contains(
                                    sc2.guid
                                )
                            ) {

                                deleteServer.add(
                                    sc2.guid
                                )
                            }
                        }
                    }
            }

        for (
            item in deleteServer
        ) {

            MmkvManager.removeServer(
                item
            )
        }

        return deleteServer.count()
    }

    /**
     * Remove all servers.
     */
    fun removeAllServer():
        Int {

        return if (
            subscriptionId.isEmpty() &&
            keywordFilter.isEmpty()
        ) {

            MmkvManager
                .removeAllServer()

        } else {

            val serversCopy =
                serversCache.toList()

            for (
                item in serversCopy
            ) {

                MmkvManager.removeServer(
                    item.guid
                )
            }

            serversCache
                .toList()
                .count()
        }
    }

    /**
     * Remove invalid servers.
     */
    fun removeInvalidServer():
        Int {

        var count =
            0

        if (
            subscriptionId.isEmpty() &&
            keywordFilter.isEmpty()
        ) {

            count +=
                MmkvManager
                    .removeInvalidServer(
                        ""
                    )

        } else {

            val serversCopy =
                serversCache.toList()

            for (
                item in serversCopy
            ) {

                count +=
                    MmkvManager
                        .removeInvalidServer(
                            item.guid
                        )
            }
        }

        return count
    }

    /**
     * Sort servers by their displayed Ping.
     *
     * RealPingWorkerService now stores the median Ping,
     * so sorting is already more resistant to spikes
     * than the old single-sample behavior.
     */
    fun sortByTestResults() {

        if (
            subscriptionId.isEmpty()
        ) {

            MmkvManager
                .decodeSubsList()
                .forEach { guid ->

                    sortByTestResultsForSub(
                        guid
                    )
                }

        } else {

            sortByTestResultsForSub(
                subscriptionId
            )
        }
    }

    /**
     * Sort one subscription by Ping.
     */
    private fun sortByTestResultsForSub(
        subId: String
    ) {

        data class ServerDelay(
            var guid: String,
            var testDelayMillis: Long
        )

        val serverDelays =
            mutableListOf<ServerDelay>()

        val serverListToSort =
            MmkvManager
                .decodeServerList(
                    subId
                )

        serverListToSort
            .forEach { key ->

                val delay =
                    MmkvManager
                        .decodeServerAffiliationInfo(
                            key
                        )
                        ?.testDelayMillis
                        ?: 0L

                serverDelays.add(
                    ServerDelay(
                        key,

                        if (
                            delay <=
                            0L
                        ) {

                            999999L

                        } else {

                            delay
                        }
                    )
                )
            }

        serverDelays.sortBy {
            it.testDelayMillis
        }

        val sortedServerList =
            serverDelays
                .map {
                    it.guid
                }
                .toMutableList()

        MmkvManager.encodeServerList(
            sortedServerList,
            subId
        )
    }

    /*
     * =========================================================
     * MojAzad quality-based initial server selection
     * =========================================================
     */

    /**
     * Read the quality result written by CoreTestService.
     */
    private fun readServerQualityResult(
        guid: String
    ): ServerQualityResult? {

        val encoded =
            MmkvManager
                .decodeSettingsString(
                    qualityResultKey(
                        guid
                    ),
                    ""
                )
                .orEmpty()
                .trim()

        if (
            encoded.isBlank()
        ) {

            return null
        }

        val parts =
            encoded.split(
                '|',
                limit = 6
            )

        if (
            parts.size <
            5
        ) {

            return null
        }

        if (
            parts[0] !=
            QUALITY_RESULT_VERSION
        ) {

            return null
        }

        val median =
            parts
                .getOrNull(
                    1
                )
                ?.toLongOrNull()
                ?: return null

        val jitter =
            parts
                .getOrNull(
                    2
                )
                ?.toLongOrNull()
                ?: 0L

        val successCount =
            parts
                .getOrNull(
                    3
                )
                ?.toIntOrNull()
                ?: 0

        val attemptCount =
            parts
                .getOrNull(
                    4
                )
                ?.toIntOrNull()
                ?: 0

        val samples =
            parts
                .getOrNull(
                    5
                )
                .orEmpty()
                .split(
                    ','
                )
                .mapNotNull {
                    it.toLongOrNull()
                }
                .filter {
                    it > 0L
                }

        return ServerQualityResult(
            guid =
                guid,

            medianMillis =
                median,

            jitterMillis =
                jitter.coerceAtLeast(
                    0L
                ),

            successCount =
                successCount.coerceAtLeast(
                    0
                ),

            attemptCount =
                attemptCount.coerceAtLeast(
                    0
                ),

            samples =
                samples
        )
    }

    /**
     * Generate the MMKV quality key used by
     * CoreTestService.
     */
    private fun qualityResultKey(
        guid: String
    ): String {

        return QUALITY_RESULT_KEY_PREFIX +
            guid
    }

    /**
     * Calculate final quality score.
     *
     * Lower = better.
     *
     * Score components:
     *
     * 1. Median Ping
     * 2. Jitter penalty
     * 3. Failed-attempt penalty
     * 4. Additional high-latency penalty
     */
    private fun calculateQualityScore(
        quality: ServerQualityResult
    ): Long {

        val median =
            quality
                .medianMillis
                .coerceAtLeast(
                    1L
                )

        val jitterPenalty =
            quality
                .jitterMillis
                .coerceAtLeast(
                    0L
                ) *
                QUALITY_JITTER_WEIGHT

        val failurePenalty =
            quality
                .failureCount
                .toLong() *
                QUALITY_FAILURE_PENALTY

        val highPingPenalty =
            if (
                median >
                QUALITY_HIGH_PING_THRESHOLD
            ) {

                (
                    median -
                        QUALITY_HIGH_PING_THRESHOLD
                    ) *
                    QUALITY_HIGH_PING_WEIGHT

            } else {

                0L
            }

        return median +
            jitterPenalty +
            failurePenalty +
            highPingPenalty
    }

    /**
     * Fallback for compatibility.
     *
     * If quality data is unexpectedly unavailable,
     * MojAzad can still use the normal positive Ping
     * instead of failing to connect completely.
     */
    private fun buildFallbackQualityResult(
        guid: String
    ): ServerQualityResult? {

        val delay =
            MmkvManager
                .decodeServerAffiliationInfo(
                    guid
                )
                ?.testDelayMillis
                ?: 0L

        if (
            delay <=
            0L
        ) {

            return null
        }

        return ServerQualityResult(
            guid =
                guid,

            medianMillis =
                delay,

            jitterMillis =
                0L,

            successCount =
                1,

            attemptCount =
                1,

            samples =
                listOf(
                    delay
                )
        )
    }

    /**
     * Find the best server from the current test batch.
     *
     * Important behavior:
     *
     * - a failed sample does NOT delete a server
     * - a server with 2/3 success can still be selected
     * - 3/3 stable servers receive an advantage
     * - one lucky low Ping is not enough to win
     * - this selection happens before connection
     * - it does not continuously switch servers afterward
     */
    private fun findBestQualityServer():
        String? {

        val candidates =
            serversCache
                .mapNotNull { server ->

                    val quality =
                        readServerQualityResult(
                            server.guid
                        )
                            ?: buildFallbackQualityResult(
                                server.guid
                            )
                            ?: return@mapNotNull null

                    if (
                        !quality.isUsable
                    ) {

                        return@mapNotNull null
                    }

                    ServerQualityCandidate(
                        quality =
                            quality,

                        score =
                            calculateQualityScore(
                                quality
                            )
                    )
                }

        if (
            candidates.isEmpty()
        ) {

            return null
        }

        /*
         * Final ordering:
         *
         * 1. quality score
         * 2. more successful attempts
         * 3. lower jitter
         * 4. lower median Ping
         *
         * This makes ties deterministic.
         */
        val best =
            candidates
                .minWithOrNull(
                    compareBy<ServerQualityCandidate> {
                        it.score
                    }
                        .thenByDescending {
                            it.quality.successCount
                        }
                        .thenBy {
                            it.quality.jitterMillis
                        }
                        .thenBy {
                            it.quality.medianMillis
                        }
                )
                ?: return null

        LogUtil.i(
            AppConfig.TAG,
            "MojAzad best initial server: " +
                "guid=${best.quality.guid}, " +
                "score=${best.score}, " +
                "median=${best.quality.medianMillis}ms, " +
                "jitter=${best.quality.jitterMillis}ms, " +
                "success=${best.quality.successCount}/" +
                "${best.quality.attemptCount}, " +
                "samples=${best.quality.samples}"
        )

        return best
            .quality
            .guid
    }

    /**
     * Called when the entire Ping batch finishes.
     */
    fun onTestsFinished() {

        viewModelScope.launch(
            Dispatchers.Default
        ) {

            /*
             * Save this before resetting the flag.
             */
            val shouldAutoConnect =
                connectBestServerAfterPing

            connectBestServerAfterPing =
                false

            /*
             * IMPORTANT:
             *
             * Automatic startup / activation / Auto Failover
             * quality tests must NEVER remove a server merely
             * because its current Ping attempt failed.
             *
             * The old Auto Remove Invalid setting is therefore
             * respected only for manual Real Ping.
             */
            if (
                !shouldAutoConnect &&
                MmkvManager.decodeSettingsBool(
                    AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST
                )
            ) {

                removeInvalidServer()
            }

            /*
             * Automatic MojAzad test always sorts.
             *
             * Manual Ping only sorts if normal Auto Sort
             * is enabled.
             */
            if (
                shouldAutoConnect ||
                MmkvManager.decodeSettingsBool(
                    AppConfig.PREF_AUTO_SORT_AFTER_TEST
                )
            ) {

                sortByTestResults()
            }

            /*
             * Automatic connection now selects the
             * best-quality server instead of merely
             * the lowest single Ping.
             */
            val bestServerGuid =
                if (
                    shouldAutoConnect
                ) {

                    findBestQualityServer()

                } else {

                    null
                }

            /*
             * Select winner once before connection.
             */
            if (
                !bestServerGuid
                    .isNullOrBlank()
            ) {

                MmkvManager.setSelectServer(
                    bestServerGuid
                )
            }

            withContext(
                Dispatchers.Main
            ) {

                /*
                 * Refresh UI after sorting.
                 */
                reloadServerList()

                /*
                 * Tell MainActivity to connect once.
                 */
                if (
                    !bestServerGuid
                        .isNullOrBlank()
                ) {

                    autoConnectBestServerAction.value =
                        true
                }
            }
        }
    }

    /**
     * MainActivity calls this after handling
     * the automatic connection event.
     */
    fun consumeAutoConnectBestServerAction() {

        autoConnectBestServerAction.value =
            false
    }

    /**
     * Initialize assets.
     */
    fun initAssets(
        assets: AssetManager
    ) {

        viewModelScope.launch(
            Dispatchers.Default
        ) {

            SettingsManager.initAssets(
                getApplication<AngApplication>(),
                assets
            )
        }
    }

    /**
     * Filter servers.
     */
    fun filterConfig(
        keyword: String
    ) {

        if (
            keyword ==
            keywordFilter
        ) {

            return
        }

        keywordFilter =
            keyword

        reloadServerList()
    }

    /**
     * Find subscription of currently-selected server.
     */
    fun findSubscriptionIdBySelect():
        String? {

        val selectedGuid =
            MmkvManager
                .getSelectServer()

        if (
            selectedGuid
                .isNullOrEmpty()
        ) {

            return null
        }

        val config =
            MmkvManager
                .decodeServerConfig(
                    selectedGuid
                )

        return config
            ?.subscriptionId
    }

    /**
     * Broadcast receiver.
     */
    private val mMsgReceiver =
        object :
            BroadcastReceiver() {

            override fun onReceive(
                ctx: Context?,
                intent: Intent?
            ) {

                when (
                    intent
                        ?.getIntExtra(
                            "key",
                            0
                        )
                ) {

                    AppConfig.MSG_STATE_RUNNING -> {

                        isRunning.value =
                            true
                    }

                    AppConfig.MSG_STATE_NOT_RUNNING -> {

                        isRunning.value =
                            false
                    }

                    AppConfig.MSG_STATE_START_SUCCESS -> {

                        getApplication<AngApplication>()
                            .toastSuccess(
                                R.string.toast_services_success
                            )

                        isRunning.value =
                            true
                    }

                    AppConfig.MSG_STATE_START_FAILURE -> {

                        val errorMessage =
                            intent
                                .getStringExtra(
                                    "content"
                                )

                        if (
                            !errorMessage
                                .isNullOrBlank()
                        ) {

                            getApplication<AngApplication>()
                                .toastError(
                                    errorMessage
                                )

                        } else {

                            getApplication<AngApplication>()
                                .toastError(
                                    R.string.toast_services_failure
                                )
                        }

                        isRunning.value =
                            false
                    }

                    AppConfig.MSG_STATE_STOP_SUCCESS -> {

                        isRunning.value =
                            false
                    }

                    AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {

                        updateTestResultAction.value =
                            intent
                                .getStringExtra(
                                    "content"
                                )
                    }

                    AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {

                        val content =
                            intent
                                .getStringExtra(
                                    "content"
                                )

                        updateListAction.value =
                            getPosition(
                                content.orEmpty()
                            )
                    }

                    AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {

                        val content =
                            intent
                                .getStringExtra(
                                    "content"
                                )

                        updateTestResultAction.value =
                            getApplication<AngApplication>()
                                .getString(
                                    R.string.connection_runing_task_left,
                                    content
                                )
                    }

                    AppConfig.MSG_MEASURE_CONFIG_FINISH -> {

                        val content =
                            intent
                                .getStringExtra(
                                    "content"
                                )

                        /*
                         * 0 = Ping/quality batch completed
                         * successfully.
                         *
                         * Cancelled/failed batches must not
                         * automatically connect.
                         */
                        if (
                            content ==
                            "0"
                        ) {

                            onTestsFinished()
                        }
                    }
                }
            }
        }
}
