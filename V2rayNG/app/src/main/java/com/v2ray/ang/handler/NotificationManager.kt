package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

object NotificationManager {

    private const val NOTIFICATION_ID = 1

    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0

    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1

    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2

    private const val NOTIFICATION_ICON_THRESHOLD = 3000

    private const val QUERY_INTERVAL_MS = 3000L

    /*
     * =========================================================
     * MojAzad V3 Traffic Dashboard
     * =========================================================
     */

    data class TrafficSnapshot(
        val uploadBytes: Long,
        val downloadBytes: Long,
        val uploadSpeedBytesPerSecond: Long,
        val downloadSpeedBytesPerSecond: Long,
        val updatedAt: Long
    )

    private var lastQueryTime =
        0L

    private var mBuilder:
        NotificationCompat.Builder? = null

    private var speedNotificationJob:
        Job? = null

    private var mNotificationManager:
        NotificationManager? = null

    /*
     * Cumulative proxy traffic for the
     * currently connected MojAzad server.
     */
    @Volatile
    private var sessionProxyUplink =
        0L

    @Volatile
    private var sessionProxyDownlink =
        0L

    @Volatile
    private var currentProxyUplinkSpeed =
        0L

    @Volatile
    private var currentProxyDownlinkSpeed =
        0L

    @Volatile
    private var trafficUpdatedAt =
        0L

    /*
     * =========================================================
     * Traffic collector
     * =========================================================
     */

    /**
     * MojAzad V3:
     *
     * Traffic collection always runs while the Core
     * is active because MainActivity Dashboard needs
     * upload/download totals even when notification
     * speed text is disabled.
     */
    fun startSpeedNotification() {

        if (
            speedNotificationJob != null ||
            CoreServiceManager.isRunning() == false
        ) {

            return
        }

        var lastZeroSpeed =
            false

        speedNotificationJob =
            CoroutineScope(
                Dispatchers.IO
            ).launch {

                while (
                    isActive
                ) {

                    val showSpeedNotification =
                        MmkvManager.decodeSettingsBool(
                            AppConfig.PREF_SPEED_ENABLED
                        )

                    lastZeroSpeed =
                        updateTrafficStatsOnce(
                            lastZeroSpeed =
                                lastZeroSpeed,
                            showSpeedNotification =
                                showSpeedNotification
                        )

                    delay(
                        QUERY_INTERVAL_MS
                    )
                }
            }
    }

    /*
     * =========================================================
     * Notification
     * =========================================================
     */

    /**
     * Shows foreground-service notification.
     *
     * A new foreground connection represents
     * a new MojAzad server session, therefore
     * dashboard traffic starts again from zero.
     */
    fun showNotification(
        currentConfig: ProfileItem?
    ) {

        val service =
            getService()
                ?: return

        resetSessionTraffic()

        /*
         * Avoid querying Core immediately
         * after the connection starts.
         */
        lastQueryTime =
            System.currentTimeMillis()

        val flags =
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT

        /*
         * Avoid MainActivity::class.java.
         *
         * Some CI builds previously produced
         * KClass.java errors, so use Android's
         * explicit class name instead.
         */
        val startMainIntent =
            Intent().apply {

                setClassName(
                    service,
                    "com.v2ray.ang.ui.MainActivity"
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }

        val contentPendingIntent =
            PendingIntent.getActivity(
                service,
                NOTIFICATION_PENDING_INTENT_CONTENT,
                startMainIntent,
                flags
            )

        val stopV2RayIntent =
            Intent(
                AppConfig.BROADCAST_ACTION_SERVICE
            ).apply {

                `package` =
                    AppConfig.ANG_PACKAGE

                putExtra(
                    "key",
                    AppConfig.MSG_STATE_STOP
                )
            }

        val stopV2RayPendingIntent =
            PendingIntent.getBroadcast(
                service,
                NOTIFICATION_PENDING_INTENT_STOP_V2RAY,
                stopV2RayIntent,
                flags
            )

        val restartV2RayIntent =
            Intent(
                AppConfig.BROADCAST_ACTION_SERVICE
            ).apply {

                `package` =
                    AppConfig.ANG_PACKAGE

                putExtra(
                    "key",
                    AppConfig.MSG_STATE_RESTART
                )
            }

        val restartV2RayPendingIntent =
            PendingIntent.getBroadcast(
                service,
                NOTIFICATION_PENDING_INTENT_RESTART_V2RAY,
                restartV2RayIntent,
                flags
            )

        val channelId =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                createNotificationChannel()

            } else {

                ""
            }

        mBuilder =
            NotificationCompat.Builder(
                service,
                channelId
            )
                .setSmallIcon(
                    R.drawable.ic_stat_name
                )
                .setContentTitle(
                    currentConfig
                        ?.remarks
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: service.getString(
                            R.string.app_name
                        )
                )
                .setPriority(
                    NotificationCompat.PRIORITY_MIN
                )
                .setOngoing(
                    true
                )
                .setShowWhen(
                    false
                )
                .setOnlyAlertOnce(
                    true
                )
                .setContentIntent(
                    contentPendingIntent
                )
                .addAction(
                    R.drawable.ic_delete_24dp,
                    service.getString(
                        R.string.notification_action_stop_v2ray
                    ),
                    stopV2RayPendingIntent
                )
                .addAction(
                    R.drawable.ic_restore_24dp,
                    service.getString(
                        R.string.title_service_restart
                    ),
                    restartV2RayPendingIntent
                )

        service.startForeground(
            NOTIFICATION_ID,
            mBuilder?.build()
        )
    }

    /**
     * Cancels notification and traffic collector.
     */
    fun cancelNotification() {

        val service =
            getService()
                ?: return

        service.stopForeground(
            Service.STOP_FOREGROUND_REMOVE
        )

        mBuilder =
            null

        speedNotificationJob
            ?.cancel()

        speedNotificationJob =
            null

        mNotificationManager =
            null

        currentProxyUplinkSpeed =
            0L

        currentProxyDownlinkSpeed =
            0L
    }

    /**
     * Stops periodic querying.
     *
     * Cumulative totals are intentionally
     * NOT reset here.
     */
    fun stopSpeedNotification() {

        speedNotificationJob
            ?.let {

                it.cancel()

                speedNotificationJob =
                    null

                currentProxyUplinkSpeed =
                    0L

                currentProxyDownlinkSpeed =
                    0L

                if (
                    MmkvManager.decodeSettingsBool(
                        AppConfig.PREF_SPEED_ENABLED
                    )
                ) {

                    updateNotification(
                        contentText = "",
                        proxyTraffic = 0L,
                        directTraffic = 0L
                    )
                }
            }
    }

    /*
     * =========================================================
     * MojAzad Dashboard API
     * =========================================================
     */

    /**
     * Returns cached session traffic without
     * querying Core again.
     *
     * This is important because Core traffic
     * counters are reset by the traffic query.
     */
    fun getTrafficSnapshot():
        TrafficSnapshot {

        return TrafficSnapshot(
            uploadBytes =
                sessionProxyUplink,
            downloadBytes =
                sessionProxyDownlink,
            uploadSpeedBytesPerSecond =
                currentProxyUplinkSpeed,
            downloadSpeedBytesPerSecond =
                currentProxyDownlinkSpeed,
            updatedAt =
                trafficUpdatedAt
        )
    }

    /**
     * Resets Dashboard traffic for
     * the new server session.
     */
    private fun resetSessionTraffic() {

        sessionProxyUplink =
            0L

        sessionProxyDownlink =
            0L

        currentProxyUplinkSpeed =
            0L

        currentProxyDownlinkSpeed =
            0L

        trafficUpdatedAt =
            System.currentTimeMillis()
    }

    /*
     * =========================================================
     * Android Notification Channel
     * =========================================================
     */

    @RequiresApi(
        Build.VERSION_CODES.O
    )
    private fun createNotificationChannel():
        String {

        val channelId =
            AppConfig.RAY_NG_CHANNEL_ID

        val channelName =
            AppConfig.RAY_NG_CHANNEL_NAME

        val channel =
            NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )

        channel.lightColor =
            Color.DKGRAY

        channel.lockscreenVisibility =
            Notification.VISIBILITY_PRIVATE

        getNotificationManager()
            ?.createNotificationChannel(
                channel
            )

        return channelId
    }

    /*
     * =========================================================
     * Notification updates
     * =========================================================
     */

    private fun updateNotification(
        contentText: String?,
        proxyTraffic: Long,
        directTraffic: Long
    ) {

        if (
            mBuilder ==
            null
        ) {

            return
        }

        when {

            proxyTraffic <
                NOTIFICATION_ICON_THRESHOLD &&
                directTraffic <
                NOTIFICATION_ICON_THRESHOLD -> {

                mBuilder
                    ?.setSmallIcon(
                        R.drawable.ic_stat_name
                    )
            }

            proxyTraffic >
                directTraffic -> {

                mBuilder
                    ?.setSmallIcon(
                        R.drawable.ic_stat_proxy
                    )
            }

            else -> {

                mBuilder
                    ?.setSmallIcon(
                        R.drawable.ic_stat_direct
                    )
            }
        }

        mBuilder
            ?.setStyle(
                NotificationCompat
                    .BigTextStyle()
                    .bigText(
                        contentText
                    )
            )

        mBuilder
            ?.setContentText(
                contentText
            )

        getNotificationManager()
            ?.notify(
                NOTIFICATION_ID,
                mBuilder?.build()
            )
    }

    private fun getNotificationManager():
        NotificationManager? {

        if (
            mNotificationManager ==
            null
        ) {

            val service =
                getService()
                    ?: return null

            mNotificationManager =
                service.getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager
        }

        return mNotificationManager
    }

    /*
     * =========================================================
     * Speed formatting
     * =========================================================
     */

    private fun appendSpeedString(
        text: StringBuilder,
        name: String?,
        up: Double,
        down: Double
    ) {

        var displayName =
            name
                ?: "no tag"

        displayName =
            displayName.take(
                min(
                    displayName.length,
                    6
                )
            )

        text.append(
            displayName
        )

        for (
            i in
            displayName.length..6 step 2
        ) {

            text.append(
                "\t"
            )
        }

        text.append(
            "•  ${up.toLong().toSpeedString()}↑  ${down.toLong().toSpeedString()}↓\n"
        )
    }

    /*
     * =========================================================
     * MojAzad V3 Traffic Collection
     * =========================================================
     */

    /**
     * Queries Core only once per interval.
     *
     * The Core query returns and resets
     * outbound counters.
     *
     * Therefore each returned value is added
     * to MojAzad session totals.
     */
    private fun updateTrafficStatsOnce(
        lastZeroSpeed: Boolean,
        showSpeedNotification: Boolean
    ): Boolean {

        val queryTime =
            System.currentTimeMillis()

        val sinceLastQueryIn =
            queryTime -
                lastQueryTime

        if (
            sinceLastQueryIn <
            QUERY_INTERVAL_MS
        ) {

            LogUtil.w(
                AppConfig.TAG,
                "Query interval too short: ${sinceLastQueryIn}ms, skipping"
            )

            return lastZeroSpeed
        }

        val sinceLastQueryInSeconds =
            sinceLastQueryIn /
                1000.0

        var proxyUplink =
            0L

        var proxyDownlink =
            0L

        var directUplink =
            0L

        var directDownlink =
            0L

        CoreServiceManager
            .queryAllOutboundTrafficStats()
            .forEach { stat ->

                when {

                    stat.tag ==
                        AppConfig.TAG_DIRECT -> {

                        when (
                            stat.direction
                        ) {

                            AppConfig.UPLINK -> {

                                directUplink +=
                                    stat.value
                            }

                            AppConfig.DOWNLINK -> {

                                directDownlink +=
                                    stat.value
                            }
                        }
                    }

                    stat.tag !=
                        AppConfig.TAG_BLOCKED -> {

                        when (
                            stat.direction
                        ) {

                            AppConfig.UPLINK -> {

                                proxyUplink +=
                                    stat.value
                            }

                            AppConfig.DOWNLINK -> {

                                proxyDownlink +=
                                    stat.value
                            }
                        }
                    }
                }
            }

        /*
         * Accumulate server-session totals.
         */
        sessionProxyUplink +=
            proxyUplink

        sessionProxyDownlink +=
            proxyDownlink

        /*
         * Current transfer speed.
         */
        if (
            sinceLastQueryInSeconds >
            0.0
        ) {

            currentProxyUplinkSpeed =
                (
                    proxyUplink /
                        sinceLastQueryInSeconds
                    )
                    .toLong()

            currentProxyDownlinkSpeed =
                (
                    proxyDownlink /
                        sinceLastQueryInSeconds
                    )
                    .toLong()

        } else {

            currentProxyUplinkSpeed =
                0L

            currentProxyDownlinkSpeed =
                0L
        }

        trafficUpdatedAt =
            queryTime

        val proxyTotal =
            proxyUplink +
                proxyDownlink

        val directTotal =
            directUplink +
                directDownlink

        val zeroSpeed =
            proxyTotal +
                directTotal ==
                0L

        /*
         * Speed notification is optional.
         *
         * Dashboard traffic collection is
         * independent from this setting.
         */
        if (
            showSpeedNotification &&
            (
                !zeroSpeed ||
                    !lastZeroSpeed
                )
        ) {

            val text =
                StringBuilder()

            appendSpeedString(
                text = text,
                name =
                    AppConfig.TAG_PROXY,
                up =
                    proxyUplink /
                        sinceLastQueryInSeconds,
                down =
                    proxyDownlink /
                        sinceLastQueryInSeconds
            )

            appendSpeedString(
                text = text,
                name =
                    AppConfig.TAG_DIRECT,
                up =
                    directUplink /
                        sinceLastQueryInSeconds,
                down =
                    directDownlink /
                        sinceLastQueryInSeconds
            )

            updateNotification(
                contentText =
                    text.toString(),
                proxyTraffic =
                    proxyTotal,
                directTraffic =
                    directTotal
            )
        }

        lastQueryTime =
            queryTime

        return zeroSpeed
    }

    /*
     * =========================================================
     * Core Service
     * =========================================================
     */

    /**
     * Returns currently active v2rayNG Service.
     */
    private fun getService():
        Service? {

        return CoreServiceManager
            .serviceControl
            ?.get()
            ?.getService()
    }
}
