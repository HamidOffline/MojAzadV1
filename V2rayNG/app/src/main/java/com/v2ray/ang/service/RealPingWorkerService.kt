package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {

    companion object {

        /*
         * MojAzad initial quality test.
         *
         * Each server is tested several times so the initial
         * selection can consider stability instead of only
         * one raw Ping result.
         */
        private const val QUALITY_TEST_ATTEMPTS =
            3
    }

    private val job =
        SupervisorJob()

    private val concurrency =
        SettingsManager.getRealPingConcurrency()

    private val dispatcher =
        Executors
            .newFixedThreadPool(
                concurrency
            )
            .asCoroutineDispatcher()

    private val scope =
        CoroutineScope(
            job +
                dispatcher +
                CoroutineName(
                    "RealPingBatchWorker"
                )
        )

    private val runningCount =
        AtomicInteger(
            0
        )

    private val totalCount =
        AtomicInteger(
            0
        )

    fun start() {

        val jobs =
            guids.map { guid ->

                totalCount
                    .incrementAndGet()

                scope.launch {

                    runningCount
                        .incrementAndGet()

                    try {

                        val result =
                            startQualityRealPing(
                                guid
                            )

                        onEvent(
                            result
                        )

                    } catch (
                        _: Throwable
                    ) {

                        /*
                         * Keep the previous behavior:
                         * one server failure must not stop
                         * the entire Ping batch.
                         */
                    } finally {

                        val count =
                            totalCount
                                .decrementAndGet()

                        val left =
                            runningCount
                                .decrementAndGet()

                        onEvent(
                            RealPingEvent.Progress(
                                "$left / $count"
                            )
                        )
                    }
                }
            }

        scope.launch {

            try {

                joinAll(
                    *jobs.toTypedArray()
                )

                onEvent(
                    RealPingEvent.Finish(
                        "0"
                    )
                )

            } catch (
                _: CancellationException
            ) {

                onEvent(
                    RealPingEvent.Finish(
                        "-1"
                    )
                )

            } finally {

                close()
            }
        }
    }

    fun cancel() {

        job.cancel()
    }

    private fun close() {

        try {

            dispatcher.close()

        } catch (
            _: Throwable
        ) {

            // ignore
        }
    }

    /**
     * Runs several Real Ping attempts for one server.
     *
     * Failed attempts are represented internally by -1
     * but are NOT treated as a reason to delete the server.
     */
    private fun startQualityRealPing(
        guid: String
    ): RealPingEvent.Result {

        val samples =
            mutableListOf<Long>()

        var attemptCount =
            0

        repeat(
            QUALITY_TEST_ATTEMPTS
        ) {

            attemptCount +=
                1

            val result =
                startRealPing(
                    guid
                )

            if (
                result > 0L
            ) {

                samples.add(
                    result
                )
            }
        }

        /*
         * No successful sample:
         *
         * Keep -1 for the current round only.
         * The server itself remains untouched.
         */
        if (
            samples.isEmpty()
        ) {

            return RealPingEvent.Result(
                guid =
                    guid,

                delayMillis =
                    -1L,

                samples =
                    emptyList(),

                medianMillis =
                    -1L,

                jitterMillis =
                    0L,

                successCount =
                    0,

                attemptCount =
                    attemptCount
            )
        }

        val sortedSamples =
            samples
                .sorted()

        val median =
            calculateMedian(
                sortedSamples
            )

        val jitter =
            calculateJitter(
                sortedSamples,
                median
            )

        /*
         * Keep delayMillis as a real Ping value for the
         * existing UI/MMKV behavior.
         *
         * Median is more representative than taking the
         * lowest single result.
         */
        return RealPingEvent.Result(
            guid =
                guid,

            delayMillis =
                median,

            samples =
                sortedSamples,

            medianMillis =
                median,

            jitterMillis =
                jitter,

            successCount =
                sortedSamples.size,

            attemptCount =
                attemptCount
        )
    }

    /**
     * Median is resistant to one abnormal Ping spike.
     *
     * Example:
     * 95 / 102 / 410
     * median = 102
     */
    private fun calculateMedian(
        samples: List<Long>
    ): Long {

        if (
            samples.isEmpty()
        ) {

            return -1L
        }

        val middle =
            samples.size /
                2

        return if (
            samples.size %
                2 ==
            1
        ) {

            samples[
                middle
            ]

        } else {

            (
                samples[
                    middle - 1
                ] +
                    samples[
                        middle
                    ]
                ) /
                2L
        }
    }

    /**
     * Mean absolute deviation from the median.
     *
     * Lower value means a more stable server.
     */
    private fun calculateJitter(
        samples: List<Long>,
        median: Long
    ): Long {

        if (
            samples.size <=
            1
        ) {

            return 0L
        }

        return samples
            .map {
                abs(
                    it -
                        median
                )
            }
            .average()
            .roundToLong()
    }

    /**
     * Executes one actual Real Ping attempt.
     */
    private fun startRealPing(
        guid: String
    ): Long {

        val retFailure =
            -1L

        val config =
            MmkvManager
                .decodeServerConfig(
                    guid
                )
                ?: return retFailure

        if (
            !config.configType
                .isComplexType() &&
            config.configType !=
            EConfigType.HYSTERIA2 &&
            config.server
                .isNotNullEmpty() &&
            config.serverPort
                ?.toIntOrNull() != null
        ) {

            val url =
                config.server
                    .orEmpty()

            val port =
                config.serverPort
                    .orEmpty()
                    .toInt()

            val tcpTime =
                SpeedtestManager
                    .socketConnectTime(
                        url,
                        port,
                        1000
                    )

            if (
                tcpTime <=
                -1L
            ) {

                return retFailure
            }
        }

        val configResult =
            CoreConfigManager
                .getV2rayConfig4Speedtest(
                    context,
                    guid
                )

        if (
            !configResult.status
        ) {

            return retFailure
        }

        return CoreNativeManager
            .measureOutboundDelay(
                configResult.content,
                SettingsManager
                    .getDelayTestUrl()
            )
    }
}
