package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.UrlContentRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

object HttpUtil {

    /**
     * Converts the domain part of a URL string to its IDN
     * (Punycode, ASCII Compatible Encoding) format.
     *
     * For example, a URL like:
     * https://例子.中国/path
     *
     * will be converted to:
     * https://xn--fsqu00a.xn--fiqs8s/path
     */
    fun toIdnUrl(
        str: String
    ): String {

        val url =
            URL(
                str
            )

        val host =
            url.host

        val asciiHost =
            IDN.toASCII(
                url.host,
                IDN.ALLOW_UNASSIGNED
            )

        return if (
            host != asciiHost
        ) {

            str.replace(
                host,
                asciiHost
            )

        } else {

            str
        }
    }

    /**
     * Converts a Unicode domain name to IDN/Punycode.
     *
     * IP addresses and ASCII domains are returned unchanged.
     */
    fun toIdnDomain(
        domain: String
    ): String {

        if (
            Utils.isPureIpAddress(
                domain
            )
        ) {

            return domain
        }

        if (
            domain.all {
                it.code < 128
            }
        ) {

            return domain
        }

        return IDN.toASCII(
            domain,
            IDN.ALLOW_UNASSIGNED
        )
    }

    /**
     * Resolves a hostname to IP addresses.
     */
    fun resolveHostToIP(
        host: String,
        ipv6Preferred: Boolean = false
    ): List<String>? {

        try {

            if (
                Utils.isPureIpAddress(
                    host
                )
            ) {

                return null
            }

            val addresses =
                InetAddress.getAllByName(
                    host
                )

            if (
                addresses.isEmpty()
            ) {

                return null
            }

            val sortedAddresses =
                if (
                    ipv6Preferred
                ) {

                    addresses.sortedWith(
                        compareByDescending {
                            it is Inet6Address
                        }
                    )

                } else {

                    addresses.sortedWith(
                        compareBy {
                            it is Inet6Address
                        }
                    )
                }

            val ipList =
                sortedAddresses
                    .mapNotNull {
                        it.hostAddress
                    }

            LogUtil.i(
                AppConfig.TAG,
                "Resolved IPs for $host: ${ipList.joinToString()}"
            )

            return ipList

        } catch (
            e: Exception
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "Failed to resolve host to IP",
                e
            )

            return null
        }
    }

    /**
     * Retrieves URL content as a String.
     */
    fun getUrlContent(
        request: UrlContentRequest
    ): String? {

        val url =
            request.url
                ?: return null

        val client =
            buildOkHttpClient(
                request.timeout,
                request.httpPort,
                request.proxyUsername,
                request.proxyPassword,
                followRedirects = true
            )

        val requestBuilder =
            Request.Builder()
                .url(
                    url
                )
                .get()
                .header(
                    "Connection",
                    "close"
                )

        if (
            request.httpPort != 0 &&
            !request.proxyUsername
                .isNullOrBlank() &&
            !request.proxyPassword
                .isNullOrBlank()
        ) {

            requestBuilder.header(
                "Proxy-Authorization",
                Credentials.basic(
                    request.proxyUsername,
                    request.proxyPassword
                )
            )
        }

        try {

            client
                .newCall(
                    requestBuilder.build()
                )
                .execute()
                .use {
                        response ->

                    if (
                        !response.isSuccessful
                    ) {

                        LogUtil.w(
                            AppConfig.TAG,
                            "Failed to get URL content, code=${response.code}"
                        )

                        return null
                    }

                    return response
                        .body
                        ?.string()
                }

        } catch (
            e: Exception
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "Failed to get URL content",
                e
            )
        }

        return null
    }

    /**
     * Retrieves URL content with custom User-Agent.
     */
    @Throws(
        IOException::class
    )
    fun getUrlContentWithUserAgent(
        request: UrlContentRequest
    ): String {

        var currentUrl =
            request.url

        var redirects =
            0

        val maxRedirects =
            3

        while (
            redirects++ <
            maxRedirects
        ) {

            if (
                currentUrl == null
            ) {

                continue
            }

            val client =
                buildOkHttpClient(
                    request.timeout,
                    request.httpPort,
                    request.proxyUsername,
                    request.proxyPassword,
                    followRedirects = false
                )

            val finalUserAgent =
                if (
                    request.userAgent
                        .isNullOrBlank()
                ) {

                    "v2rayNG/${BuildConfig.VERSION_NAME}"

                } else {

                    request.userAgent
                }

            val requestBuilder =
                Request.Builder()
                    .url(
                        currentUrl
                    )
                    .get()
                    .header(
                        "User-agent",
                        finalUserAgent
                    )
                    .header(
                        "Connection",
                        "close"
                    )

            applyEmbeddedBasicAuthHeader(
                currentUrl,
                requestBuilder
            )

            if (
                request.httpPort != 0 &&
                !request.proxyUsername
                    .isNullOrBlank() &&
                !request.proxyPassword
                    .isNullOrBlank()
            ) {

                requestBuilder.header(
                    "Proxy-Authorization",
                    Credentials.basic(
                        request.proxyUsername,
                        request.proxyPassword
                    )
                )
            }

            client
                .newCall(
                    requestBuilder.build()
                )
                .execute()
                .use {
                        response ->

                    when {

                        response.isRedirect -> {

                            val location =
                                response.header(
                                    "Location"
                                )

                            if (
                                location.isNullOrEmpty()
                            ) {

                                throw IOException(
                                    "Redirect location not found"
                                )
                            }

                            currentUrl =
                                resolveLocation(
                                    currentUrl,
                                    location
                                )

                            if (
                                currentUrl.isNullOrEmpty()
                            ) {

                                throw IOException(
                                    "Failed to resolve redirect location"
                                )
                            }

                            continue
                        }

                        response.isSuccessful -> {

                            return response
                                .body
                                ?.string()
                                ?: ""
                        }

                        else -> {

                            throw IOException(
                                "Request failed with status code ${response.code}"
                            )
                        }
                    }
                }
        }

        throw IOException(
            "Too many redirects"
        )
    }

    private fun applyEmbeddedBasicAuthHeader(
        rawUrl: String,
        requestBuilder: Request.Builder
    ) {

        val parsed =
            runCatching {

                URL(
                    rawUrl
                )

            }
                .getOrNull()
                ?: return

        parsed.userInfo
            ?.let {
                    userInfo ->

                val colon =
                    userInfo.indexOf(
                        ':'
                    )

                val rawUser =
                    if (
                        colon >= 0
                    ) {

                        userInfo.substring(
                            0,
                            colon
                        )

                    } else {

                        userInfo
                    }

                val rawPass =
                    if (
                        colon >= 0
                    ) {

                        userInfo.substring(
                            colon + 1
                        )

                    } else {

                        ""
                    }

                val user =
                    runCatching {

                        Utils.decodeURIComponent(
                            rawUser
                        )

                    }
                        .getOrDefault(
                            rawUser
                        )

                val pass =
                    runCatching {

                        Utils.decodeURIComponent(
                            rawPass
                        )

                    }
                        .getOrDefault(
                            rawPass
                        )

                requestBuilder.header(
                    "Authorization",
                    Credentials.basic(
                        user,
                        pass
                    )
                )
            }
    }

    private fun buildOkHttpClient(
        timeout: Int,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        followRedirects: Boolean
    ): OkHttpClient {

        val builder =
            OkHttpClient.Builder()
                .connectTimeout(
                    timeout.toLong(),
                    TimeUnit.MILLISECONDS
                )
                .readTimeout(
                    timeout.toLong(),
                    TimeUnit.MILLISECONDS
                )
                .followRedirects(
                    followRedirects
                )
                .followSslRedirects(
                    followRedirects
                )

        if (
            httpPort != 0
        ) {

            builder.proxy(
                Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress(
                        LOOPBACK,
                        httpPort
                    )
                )
            )

            if (
                !proxyUsername
                    .isNullOrBlank() &&
                !proxyPassword
                    .isNullOrBlank()
            ) {

                builder.proxyAuthenticator {
                        _,
                        response ->

                    if (
                        response.request
                            .header(
                                "Proxy-Authorization"
                            ) != null
                    ) {

                        null

                    } else {

                        response.request
                            .newBuilder()
                            .header(
                                "Proxy-Authorization",
                                Credentials.basic(
                                    proxyUsername,
                                    proxyPassword
                                )
                            )
                            .build()
                    }
                }
            }
        }

        return builder.build()
    }

    private fun resolveLocation(
        baseUrl: String,
        raw: String
    ): String? {

        return try {

            val locUri =
                URI(
                    raw
                )

            val baseUri =
                URI(
                    baseUrl
                )

            val resolved =
                if (
                    locUri.isAbsolute
                ) {

                    locUri

                } else {

                    baseUri.resolve(
                        locUri
                    )
                }

            resolved
                .toURL()
                .toString()

        } catch (
            _: Exception
        ) {

            try {

                URL(
                    raw
                )
                    .toString()

            } catch (
                _: MalformedURLException
            ) {

                try {

                    URL(
                        URL(
                            baseUrl
                        ),
                        raw
                    )
                        .toString()

                } catch (
                    _: MalformedURLException
                ) {

                    null
                }
            }
        }
    }

    /*
     * =========================================================
     * MojAzad V3 File Download
     * =========================================================
     */

    /**
     * Backward-compatible download method.
     *
     * Existing parts of the project can continue calling:
     *
     * downloadToFile(request, file)
     *
     * without providing a progress callback.
     */
    fun downloadToFile(
        request: UrlContentRequest,
        targetFile: File
    ): Boolean {

        return downloadToFile(
            request = request,
            targetFile = targetFile,
            onProgress = null
        )
    }

    /**
     * MojAzad V3
     *
     * Downloads a file while reporting real progress.
     *
     * Callback values:
     *
     * downloadedBytes:
     * Number of bytes already written.
     *
     * totalBytes:
     * Total file size reported by the server.
     * Can be -1 when the server does not provide Content-Length.
     *
     * progressPercent:
     * 0..100 when total size is known.
     * null when total size is unknown.
     */
    fun downloadToFile(
        request: UrlContentRequest,
        targetFile: File,
        onProgress: ((
            downloadedBytes: Long,
            totalBytes: Long,
            progressPercent: Int?
        ) -> Unit)?
    ): Boolean {

        val url =
            request.url
                ?: return false

        val client =
            buildOkHttpClient(
                request.timeout,
                request.httpPort,
                request.proxyUsername,
                request.proxyPassword,
                followRedirects = true
            )

        val requestBuilder =
            Request.Builder()
                .url(
                    url
                )
                .get()
                .header(
                    "Connection",
                    "close"
                )

        if (
            request.httpPort != 0 &&
            !request.proxyUsername
                .isNullOrBlank() &&
            !request.proxyPassword
                .isNullOrBlank()
        ) {

            requestBuilder.header(
                "Proxy-Authorization",
                Credentials.basic(
                    request.proxyUsername,
                    request.proxyPassword
                )
            )
        }

        return try {

            client
                .newCall(
                    requestBuilder.build()
                )
                .execute()
                .use {
                        response ->

                    if (
                        !response.isSuccessful
                    ) {

                        LogUtil.w(
                            AppConfig.TAG,
                            "Failed to download file, code=${response.code}, url=$url"
                        )

                        return false
                    }

                    val body =
                        response.body
                            ?: return false

                    val totalBytes =
                        body.contentLength()

                    var downloadedBytes =
                        0L

                    var lastReportedPercent =
                        -1

                    /*
                     * Initial progress.
                     */
                    if (
                        totalBytes >
                        0L
                    ) {

                        onProgress?.invoke(
                            0L,
                            totalBytes,
                            0
                        )

                    } else {

                        onProgress?.invoke(
                            0L,
                            totalBytes,
                            null
                        )
                    }

                    body.byteStream()
                        .use {
                                input ->

                            targetFile
                                .outputStream()
                                .buffered()
                                .use {
                                        output ->

                                    val buffer =
                                        ByteArray(
                                            64 * 1024
                                        )

                                    while (
                                        true
                                    ) {

                                        val read =
                                            input.read(
                                                buffer
                                            )

                                        if (
                                            read ==
                                            -1
                                        ) {

                                            break
                                        }

                                        output.write(
                                            buffer,
                                            0,
                                            read
                                        )

                                        downloadedBytes +=
                                            read.toLong()

                                        if (
                                            totalBytes >
                                            0L
                                        ) {

                                            val percent =
                                                (
                                                    downloadedBytes *
                                                        100L /
                                                        totalBytes
                                                    )
                                                    .toInt()
                                                    .coerceIn(
                                                        0,
                                                        100
                                                    )

                                            /*
                                             * Report only when integer
                                             * percentage changes.
                                             *
                                             * This avoids flooding the UI
                                             * thread with callbacks.
                                             */
                                            if (
                                                percent !=
                                                lastReportedPercent
                                            ) {

                                                lastReportedPercent =
                                                    percent

                                                onProgress?.invoke(
                                                    downloadedBytes,
                                                    totalBytes,
                                                    percent
                                                )
                                            }

                                        } else {

                                            /*
                                             * Content-Length is unknown.
                                             *
                                             * Report bytes so the caller
                                             * can still show activity.
                                             */
                                            onProgress?.invoke(
                                                downloadedBytes,
                                                totalBytes,
                                                null
                                            )
                                        }
                                    }

                                    output.flush()
                                }
                        }

                    /*
                     * Always report completion.
                     */
                    if (
                        totalBytes >
                        0L
                    ) {

                        onProgress?.invoke(
                            downloadedBytes,
                            totalBytes,
                            100
                        )

                    } else {

                        onProgress?.invoke(
                            downloadedBytes,
                            totalBytes,
                            null
                        )
                    }

                    true
                }

        } catch (
            e: Exception
        ) {

            LogUtil.e(
                AppConfig.TAG,
                "Failed to download file: $url",
                e
            )

            false
        }
    }
  /**
 * MojAzad Subscription Header Reader
 *
 * Returns:
 * Pair(
 *   subscription content,
 *   response headers
 * )
 *
 * Used for:
 * subscription-userinfo
 */
fun getUrlContentWithHeaders(
    request: UrlContentRequest
): Pair<String, Map<String, String>> {

    val url =
        request.url
            ?: return "" to emptyMap()


    val client =
        buildOkHttpClient(
            request.timeout,
            request.httpPort,
            request.proxyUsername,
            request.proxyPassword,
            followRedirects = true
        )


    val userAgent =
        if (
            request.userAgent
                .isNullOrBlank()
        ) {

            "v2rayNG/${BuildConfig.VERSION_NAME}"

        } else {

            request.userAgent
        }


    val requestBuilder =
        Request.Builder()
            .url(
                url
            )
            .get()
            .header(
                "User-Agent",
                userAgent
            )
            .header(
                "Connection",
                "close"
            )


    applyEmbeddedBasicAuthHeader(
        url,
        requestBuilder
    )


    if (
        request.httpPort != 0 &&
        !request.proxyUsername
            .isNullOrBlank() &&
        !request.proxyPassword
            .isNullOrBlank()
    ) {

        requestBuilder.header(
            "Proxy-Authorization",
            Credentials.basic(
                request.proxyUsername,
                request.proxyPassword
            )
        )
    }


    return try {

        client
            .newCall(
                requestBuilder.build()
            )
            .execute()
            .use { response ->


                if (
                    !response.isSuccessful
                ) {

                    LogUtil.w(
                        AppConfig.TAG,
                        "Subscription header request failed code=${response.code}"
                    )

                    return "" to emptyMap()
                }


                val headers =
                    response.headers
                        .toMultimap()
                        .mapValues {

                            it.value
                                .joinToString(";")
                        }


                val body =
                    response.body
                        ?.string()
                        .orEmpty()


                body to headers
            }


    } catch (
        e: Exception
    ) {

        LogUtil.e(
            AppConfig.TAG,
            "Failed reading subscription headers",
            e
        )

        "" to emptyMap()
    }
}
}
