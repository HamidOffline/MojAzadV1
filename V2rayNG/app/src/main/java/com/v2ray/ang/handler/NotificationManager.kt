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
import com.v2ray.ang.ui.MainActivity
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
     * MojAzad V3
     *
     * Traffic information for the current server session.
     *
     * Core traffic counters are reset every time
     * queryAllOutboundTrafficStats() is called,
     * therefore MojAzad keeps cumulative totals here.
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
     * MojAzad V3 session traffic.
     *
     * Proxy traffic is used for the dashboard,
     * because it represents traffic going through
     * the selected VPN/proxy server.
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

    /**
     * Starts traffic collection and,
     * when enabled in settings,
     * updates the speed notification.
     *
     * MojAzad V3:
     * Traffic collection now runs even if the user
     * disabled speed text in the notification,
     * because the connection dashboard needs
     * accurate upload/download totals.
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

                while (isActive) {

                    val showSpeedNotification =
                        MmkvManager.decodeSettingsBool(
                            AppConfig.PREF_SPEED_ENABLED
                        )

                    lastZeroSpeed =
                        updateTrafficStatsOnce(
                            lastZeroSpeed,
                            showSpeedNotification
                        )

                    delay(
                        QUERY_INTERVAL_MS
                    )
                }
            }
    }

    /**
     * Shows the foreground notification.
     *
     * A new notification means a new VPN server session,
     * so MojAzad resets dashboard traffic totals here.
     */
    fun showNotification(
        currentConfig: ProfileItem?
    ) {

        val service =
            getService()
                ?: return

        /*
         * MojAzad V3:
         * Every new server connection starts
         * traffic counters from zero.
         *
         * This also makes Auto Failover visible:
         * when a new server starts, dashboard
         * traffic starts from zero again.
         */
        resetSessionTraffic()

        /*
         * Avoid querying stats immediately after
         * the core starts.
         */
        lastQueryTime =
            System.currentTimeMillis()

        val flags =
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent =
            Intent(
                service,
                MainActivity::class.java
            )

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
            )

        stopV2RayIntent.`package` =
            AppConfig.ANG_PACKAGE

        stopV2RayIntent.putExtra(
            "key",
            AppConfig.MSG_STATE_STOP
        )

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
            )

        restartV2RayIntent.`package` =
            AppConfig.ANG_PACKAGE

        restartV2RayIntent.putExtra(
            "key",
            AppConfig.MSG_STATE_RESTART
        )

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
                    currentConfig?.remarks
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
     * Cancels notification and stops traffic collector.
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
     * Totals are intentionally NOT reset.
     *
     * For example, when screen turns off,
     * traffic generated while the screen is off
     * will still be returned by Core the next time
     * querying resumes.
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
                        "",
                        0,
                        0
                    )
                }
            }
    }

    /**
     * MojAzad V3
     *
     * Returns current VPN-server traffic totals
     * without querying Core again.
     *
     * This is important:
     * MainActivity must never call Core traffic query
     * directly because doing so would reset the counters
     * used by the notification collector.
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
     * MojAzad V3
     *
     * Resets counters for a new server connection.
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

    /**
     * Creates notification channel.
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

        val chan =
            NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            )

        chan.lightColor =
            Color.DKGRAY

        chan.importance =
            NotificationManager.IMPORTANCE_NONE

        chan.lockscreenVisibility =
            Notification.VISIBILITY_PRIVATE

        getNotificationManager()
            ?.createNotificationChannel(
                chan
            )

        return channelId
    }

    /**
     * Updates notification with traffic speed.
     */
    private fun updateNotification(
        contentText: String?,
        proxyTraffic: Long,
        directTraffic: Long
    ) {

        if (
            mBuilder == null
        ) {
            return
        }

        if (
            proxyTraffic <
                NOTIFICATION_ICON_THRESHOLD &&
            directTraffic <
                NOTIFICATION_ICON_THRESHOLD
        ) {

            mBuilder?.setSmallIcon(
                R.drawable.ic_stat_name
            )

        } else if (
            proxyTraffic >
            directTraffic
        ) {

            mBuilder?.setSmallIcon(
                R.drawable.ic_stat_proxy
            )

        } else {

            mBuilder?.setSmallIcon(
                R.drawable.ic_stat_direct
            )
        }

        mBuilder?.setStyle(
            NotificationCompat
                .BigTextStyle()
                .bigText(
                    contentText
                )
        )

        mBuilder?.setContentText(
            contentText
        )

        getNotificationManager()
            ?.notify(
                NOTIFICATION_ID,
                mBuilder?.build()
            )
    }

    /**
     * Gets Android notification manager.
     */
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

    /**
     * Formats speed text for notification.
     */
    private fun appendSpeedString(
        text: StringBuilder,
        name: String?,
        up: Double,
        down: Double
    ) {

        var n =
            name ?: "no tag"

        n =
            n.take(
                min(
                    n.length,
                    6
                )
            )

        text.append(
            n
        )

        for (
            i in
            n.length..6 step 2
        ) {

            text.append(
                "\t"
            )
        }

        text.append(
            "•  ${up.toLong().toSpeedString()}↑  ${down.toLong().toSpeedString()}↓\n"
        )
    }

    /**
     * MojAzad V3 traffic collector.
     *
     * Queries Core once, then:
     * - keeps cumulative VPN traffic totals
     * - keeps current upload/download speed
     * - optionally updates notification
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

        /*
         * Prevent excessive Core queries.
         */
        if (
            sinceLastQueryIn <
            QUERY_INTERVAL_MS
        ) {

            LogUtil.w(
                AppConfig.TAG,
                "Query interval too short: ${sinceLastQueryIn}ms, skipping"
            )

            lastQueryTime =
                queryTime

           
