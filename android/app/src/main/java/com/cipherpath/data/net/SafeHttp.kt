package com.cipherpath.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class UnsafeRequest(msg: String) : IOException(msg)
class HttpStatusException(val code: Int, msg: String) : IOException(msg)

/**
 * Outbound HTTP for job ingestion with SSRF defences mirroring backend/app/netsafe.py:
 * https only, fixed host allowlist (plus the user's configured backend), board slugs validated,
 * DNS answers must be public addresses (ATS hosts), redirects re-validated, body size capped.
 */
class SafeHttp(private val extraAllowedHost: () -> String? = { null }) {

    companion object {
        val ATS_HOSTS = setOf(
            "boards-api.greenhouse.io", "api.lever.co", "api.eu.lever.co",
            "api.ashbyhq.com", "api.smartrecruiters.com",
        )
        val SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
        const val MAX_BYTES = 15L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun safeSlug(slug: String): String {
            if (!SLUG.matches(slug) || slug.contains("..")) throw UnsafeRequest("invalid board slug")
            return slug
        }

        fun isPublic(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                addr.isAnyLocalAddress || addr.isMulticastAddress) return false
            val b = addr.address
            if (addr is Inet4Address) {
                val a0 = b[0].toInt() and 0xff
                val a1 = b[1].toInt() and 0xff
                if (a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224) return false
                if (a0 == 100 && a1 in 64..127) return false // CGNAT
                if (a0 == 169 && a1 == 254) return false
                if (a0 == 172 && a1 in 16..31) return false
                if (a0 == 192 && a1 == 168) return false
                if (a0 == 198 && a1 in 18..19) return false
            }
            if (addr is Inet6Address) {
                val first = b[0].toInt() and 0xff
                if ((first and 0xfe) == 0xfc) return false // ULA fc00::/7
                // IPv4-mapped ::ffff:a.b.c.d
                if (b.take(10).all { it.toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff) {
                    return isPublic(InetAddress.getByAddress(b.copyOfRange(12, 16)))
                }
            }
            return true
        }
    }

    private val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addrs = Dns.SYSTEM.lookup(hostname)
            if (hostname in ATS_HOSTS && addrs.any { !isPublic(it) }) throw UnsafeRequest("$hostname resolves to a non-public address")
            return addrs
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(publicDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun validate(url: HttpUrl) {
        if (url.scheme != "https") throw UnsafeRequest("only https is allowed")
        val host = url.host.lowercase()
        val extra = extraAllowedHost()?.lowercase()
        if (host !in ATS_HOSTS && host != extra) throw UnsafeRequest("host not allowed: $host")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw UnsafeRequest("credentials in URL")
        if (url.port != 443 && host in ATS_HOSTS) throw UnsafeRequest("non-standard port")
    }

    fun getJson(url: HttpUrl, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(request("GET", url, null, headers))

    fun postJson(url: HttpUrl, body: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(request("POST", url, body.toRequestBody("application/json".toMediaType()), headers))

    fun postRaw(url: HttpUrl, body: RequestBody, headers: Map<String, String>): JsonElement =
        json.parseToJsonElement(request("POST", url, body, headers))

    private fun request(method: String, start: HttpUrl, body: RequestBody?, headers: Map<String, String>): String {
        var url = start
        repeat(4) {
            validate(url)
            val req = Request.Builder().url(url).method(method, body)
                .header("User-Agent", "CipherPath-Android/1.0")
                .header("Accept", "application/json")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isRedirect) {
                    val loc = resp.header("Location") ?: throw UnsafeRequest("redirect without location")
                    url = url.resolve(loc) ?: throw UnsafeRequest("bad redirect")
                    return@repeat
                }
                if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code} from ${url.host}")
                val src = resp.body?.byteStream() ?: return ""
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw UnsafeRequest("response too large")
                    out.write(buf, 0, n)
                }
                return out.toString("UTF-8")
            }
        }
        throw UnsafeRequest("too many redirects")
    }

    fun url(s: String): HttpUrl = s.toHttpUrlOrNull() ?: throw UnsafeRequest("bad url")
}
