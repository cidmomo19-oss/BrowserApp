package com.example

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DnsManager manages DNS-over-HTTPS (DoH) using Cloudflare (1.1.1.1 / 1.0.0.1)
 * to circumvent local ISP DNS hijacking, domain blocking, and filtering.
 */
object DnsManager {

    private const val CLOUDFLARE_DOH_URL = "https://cloudflare-dns.com/dns-query"
    private val CLOUDFLARE_IPS = listOf(
        InetAddress.getByName("1.1.1.1"),
        InetAddress.getByName("1.0.0.1"),
        InetAddress.getByName("2606:4700:4700::1111"),
        InetAddress.getByName("2606:4700:4700::1001")
    )

    private var okHttpClientInstance: OkHttpClient? = null

    /**
     * Builds or returns an OkHttpClient configured with Cloudflare DnsOverHttps.
     */
    fun getClient(context: Context? = null): OkHttpClient {
        okHttpClientInstance?.let { return it }

        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val dnsOverHttps = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(CLOUDFLARE_DOH_URL.toHttpUrl())
            .bootstrapDnsHosts(CLOUDFLARE_IPS)
            .includeIPv6(true)
            .build()

        val client = OkHttpClient.Builder()
            .dns(dnsOverHttps)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        okHttpClientInstance = client
        return client
    }
}
