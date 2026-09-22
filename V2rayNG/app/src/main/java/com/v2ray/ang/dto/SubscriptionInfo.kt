package com.v2ray.ang.dto

data class SubscriptionInfo(

    val uploadBytes: Long = 0L,

    val downloadBytes: Long = 0L,

    val totalBytes: Long = 0L,

    val expireTime: Long = -1L

)
