package com.v2ray.ang.dto.entities

data class SubscriptionItem(

    var remarks: String = "",

    var url: String = "",

    var enabled: Boolean = true,

    val addedTime: Long =
        System.currentTimeMillis(),

    var lastUpdated: Long = -1,

    var autoUpdate: Boolean = false,

    /*
     * Update interval in minutes
     */
    var updateInterval: Long = 1440,

    var prevProfile: String? = null,

    var nextProfile: String? = null,

    var filter: String? = null,

    var allowInsecureUrl: Boolean = false,

    var userAgent: String? = null,


    /*
     * =================================
     * MojAzad Subscription Usage
     * =================================
     *
     * Values are received from
     * subscription-userinfo header
     *
     * upload:
     * total uploaded traffic
     *
     * download:
     * total downloaded traffic
     *
     * total:
     * subscription traffic limit
     *
     * expire:
     * unix timestamp
     */

    var uploadBytes: Long = 0L,

    var downloadBytes: Long = 0L,

    var totalBytes: Long = 0L,

    var expireTime: Long = -1L,
)
