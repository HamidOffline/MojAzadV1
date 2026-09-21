package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.NotificationHelper
import java.util.Collections

class CoreTestService : Service() {

    companion object {

        /*
         * MojAzad initial server quality data.
         *
         * Stored in MMKV so MainViewModel can read the result
         * even when CoreTestService runs in another process.
         *
         * Format:
         *
         * v1|median|jitter|successCount|attemptCount|sample1,sample2,...
         */
        private const val QUALITY_RESULT_KEY_PREFIX =
            "mojazad_initial_quality_"

        private const val QUALITY_RESULT_VERSION =
            "v1"
    }

    /*
     * Manage active batch workers so each batch
     * is independent and cancellable.
     */
    private val activeWorkers =
        Collections.synchronizedList(
            mutableListOf<RealPingWorkerService>()
        )

    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {

        super.onCreate()

        CoreNativeManager.initCoreEnv(
            this
        )
    }

    /**
     * Binds the service.
     *
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {

        LogUtil.i(
            AppConfig.TAG,
            "CoreTestService is being destroyed, cancelling " +
                "${activeWorkers.size} active workers"
        )

        val snapshot =
            ArrayList(
                activeWorkers
            )

        snapshot.forEach {
            it.cancel()
        }

        activeWorkers.clear()

        NotificationHelper.stopForeground(
            this
        )

        super.onDestroy()
    }

    /**
     * Handles the start command for the service.
     *
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val message =
            intent
                ?.serializable<TestServiceMessage>(
                    "content"
                )

        if (
            message == null
        ) {

            stopSelf(
                startId
            )

            return START_NOT_STICKY
        }

        when (
            message.key
        ) {

            AppConfig.MSG_MEASURE_CONFIG_START -> {

                handleMeasureStart(
                    message,
                    startId
                )
            }

            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> {

                handleMeasureCancel()
            }

            else -> {

                NotificationHelper.stopForeground(
                    this
                )

                stopSelf(
                    startId
                )
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Starts a new Real Ping / quality-test batch.
     */
    private fun handleMeasureStart(
        message: TestServiceMessage,
        startId: Int
    ) {

        LogUtil.i(
            AppConfig.TAG,
            "CoreTestService starting worker subscription " +
                message.subscriptionId
        )

        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(
                R.string.app_name
            ),
            getString(
                R.string.title_real_ping_all_server
            )
        )

        val guidsList =
            when {

                message.serverGuids
                    .isNotEmpty() -> {

                    message.serverGuids
                }

                message.subscriptionId
                    .isNotEmpty() -> {

                    MmkvManager.decodeServerList(
                        message.subscriptionId
                    )
                }

                else -> {

                    MmkvManager
                        .decodeAllServerList()
                }
            }

        if (
            guidsList.isNotEmpty()
        ) {

            /*
             * Clear quality information from the previous
             * batch before starting a new measurement.
             *
             * This prevents stale quality data from being
             * used if a server fails in the current batch.
             *
             * The server itself is NOT removed.
             */
            guidsList.forEach { guid ->

                clearQualityResult(
                    guid
                )
            }

            lateinit var worker:
                RealPingWorkerService

            worker =
                RealPingWorkerService(
                    context =
                        this,

                    guids =
                        guidsList,

                    onEvent = { event ->

                        handleWorkerEvent(
                            event
                        ) {

                            activeWorkers.remove(
                                worker
                            )
                        }
                    }
                )

            activeWorkers.add(
                worker
            )

            worker.start()

        } else {

            NotificationHelper.stopForeground(
                this
            )

            stopSelf(
                startId
            )
        }
    }

    /**
     * Handles events returned by RealPingWorkerService.
     */
    private fun handleWorkerEvent(
        event: RealPingEvent,
        onWorkerDone: () -> Unit
    ) {

        when (
            event
        ) {

            is RealPingEvent.Progress -> {

                NotificationHelper.updateNotification(
                    channelType =
                        NotificationChannelType.CORE_TEST,

                    context =
                        this,

                    content =
                        getString(
                            R.string.connection_runing_task_left,
                            event.text
                        )
                )

                MessageUtil.sendMsg2UI(
                    this,
                    AppConfig.MSG_MEASURE_CONFIG_NOTIFY,
                    event.text
                )
            }

            is RealPingEvent.Result -> {

                /*
                 * Preserve existing MojAzad/v2rayNG behavior.
                 *
                 * delayMillis is currently the median Ping
                 * produced by RealPingWorkerService.
                 *
                 * UI, sorting and Server Health can therefore
                 * continue reading testDelayMillis normally.
                 */
                MmkvManager
                    .encodeServerTestDelayMillis(
                        event.guid,
                        event.delayMillis
                    )

                /*
                 * Save the additional quality measurements
                 * separately.
                 *
                 * This lets MainViewModel make the final
                 * quality-based initial server selection
                 * without changing what the UI considers Ping.
                 */
                saveQualityResult(
                    event
                )

                /*
                 * Keep the existing UI update protocol.
                 *
                 * Only the GUID is sent, so GroupServerFragment
                 * and MainViewModel's current getPosition()
                 * behavior remain compatible.
                 */
                MessageUtil.sendMsg2UI(
                    this,
                    AppConfig.MSG_MEASURE_CONFIG_SUCCESS,
                    event.guid
                )
            }

            is RealPingEvent.Finish -> {

                MessageUtil.sendMsg2UI(
                    this,
                    AppConfig.MSG_MEASURE_CONFIG_FINISH,
                    event.status
                )

                onWorkerDone()

                if (
                    activeWorkers.isEmpty()
                ) {

                    NotificationHelper.stopForeground(
                        this
                    )

                    stopSelf()
                }
            }
        }
    }

    /**
     * Persists one server's quality-test result.
     *
     * Example:
     *
     * v1|118|4|3|3|114,118,122
     *
     * This data is only for initial server selection.
     * It never causes automatic server deletion.
     */
    private fun saveQualityResult(
        event: RealPingEvent.Result
    ) {

        val samples =
            event.samples
                .joinToString(
                    ","
                )

        val value =
            listOf(
                QUALITY_RESULT_VERSION,
                event.medianMillis.toString(),
                event.jitterMillis.toString(),
                event.successCount.toString(),
                event.attemptCount.toString(),
                samples
            )
                .joinToString(
                    "|"
                )

        MmkvManager.encodeSettings(
            qualityResultKey(
                event.guid
            ),
            value
        )

        LogUtil.i(
            AppConfig.TAG,
            "MojAzad quality result: " +
                "guid=${event.guid}, " +
                "median=${event.medianMillis}ms, " +
                "jitter=${event.jitterMillis}ms, " +
                "success=${event.successCount}/${event.attemptCount}"
        )
    }

    /**
     * Clears previous quality data for one server.
     *
     * This clears only MojAzad's temporary quality result.
     * It does NOT delete the server or configuration.
     */
    private fun clearQualityResult(
        guid: String
    ) {

        MmkvManager.encodeSettings(
            qualityResultKey(
                guid
            ),
            ""
        )
    }

    /**
     * Generates the MMKV key used for one server.
     */
    private fun qualityResultKey(
        guid: String
    ): String {

        return QUALITY_RESULT_KEY_PREFIX +
            guid
    }

    /**
     * Cancels the current test batch.
     */
    private fun handleMeasureCancel() {

        LogUtil.i(
            AppConfig.TAG,
            "CoreTestService received cancel message, cancelling " +
                "${activeWorkers.size} active workers"
        )

        val snapshot =
            ArrayList(
                activeWorkers
            )

        snapshot.forEach {
            it.cancel()
        }

        activeWorkers.clear()

        NotificationHelper.stopForeground(
            this
        )

        stopSelf()
    }
}
