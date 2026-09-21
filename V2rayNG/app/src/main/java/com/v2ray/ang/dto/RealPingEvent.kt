package com.v2ray.ang.dto

sealed class RealPingEvent {

    /**
     * Periodic progress update while the batch
     * is still running.
     */
    data class Progress(
        val text: String
    ) : RealPingEvent()

    /**
     * Result for a single server.
     *
     * delayMillis:
     * The representative Ping value that can continue
     * to be stored and displayed by the existing UI.
     *
     * samples:
     * Successful Real Ping samples collected for
     * quality evaluation.
     *
     * medianMillis:
     * Median of successful samples.
     *
     * jitterMillis:
     * Ping variation between successful samples.
     * Lower is better.
     *
     * successCount / attemptCount:
     * Used to calculate test reliability.
     *
     * IMPORTANT:
     * A failed attempt does NOT mean the server
     * should be deleted.
     */
    data class Result(
        val guid: String,
        val delayMillis: Long,

        val samples: List<Long> =
            if (
                delayMillis > 0L
            ) {
                listOf(
                    delayMillis
                )
            } else {
                emptyList()
            },

        val medianMillis: Long =
            delayMillis,

        val jitterMillis: Long =
            0L,

        val successCount: Int =
            if (
                delayMillis > 0L
            ) {
                1
            } else {
                0
            },

        val attemptCount: Int =
            1

    ) : RealPingEvent() {

        /**
         * Number of failed attempts.
         *
         * Failure only affects quality evaluation.
         * It never means automatic deletion.
         */
        val failureCount: Int
            get() =
                (
                    attemptCount -
                        successCount
                    )
                    .coerceAtLeast(
                        0
                    )

        /**
         * Whether this server produced at least
         * one usable result in the current test.
         */
        val hasSuccessfulSample: Boolean
            get() =
                successCount > 0 &&
                    medianMillis > 0L
    }

    /**
     * The entire batch has finished
     * or has been cancelled.
     */
    data class Finish(
        val status: String
    ) : RealPingEvent()
}
