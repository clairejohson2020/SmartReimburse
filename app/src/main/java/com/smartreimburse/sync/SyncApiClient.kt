package com.smartreimburse.sync

import android.util.Base64
import com.smartreimburse.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PairingInfo(
    val pairingId: String,
    val code: String,
    val requestSecret: String,
    val expiresAt: Long
)

data class SyncSession(val accessToken: String, val userId: String, val expiresAt: Long)

class SyncApiClient {
    val isConfigured: Boolean get() = BuildConfig.SYNC_API_BASE_URL.isNotBlank()

    suspend fun createPairing(deviceId: String, deviceName: String): PairingInfo {
        val data = request(
            method = "POST",
            path = "/pairings",
            body = JSONObject().put("deviceId", deviceId).put("deviceName", deviceName)
        )
        return PairingInfo(
            pairingId = data.getString("pairingId"),
            code = data.getString("code"),
            requestSecret = data.getString("requestSecret"),
            expiresAt = data.getLong("expiresAt")
        )
    }

    suspend fun exchangePairing(pairingId: String, requestSecret: String): SyncSession {
        val data = request(
            method = "POST",
            path = "/pairings/exchange",
            body = JSONObject().put("pairingId", pairingId).put("requestSecret", requestSecret)
        )
        return SyncSession(
            accessToken = data.getString("accessToken"),
            userId = data.getString("userId"),
            expiresAt = data.getLong("expiresAt")
        )
    }

    suspend fun pull(
        token: String,
        entity: String,
        since: Long,
        until: Long,
        offset: Int
    ): JSONObject = request(
        method = "GET",
        path = "/sync/pull",
        token = token,
        query = mapOf(
            "entity" to entity,
            "since" to since.toString(),
            "until" to until.toString(),
            "offset" to offset.toString(),
            "pageSize" to "50"
        )
    )

    suspend fun push(token: String, mutations: JSONArray): JSONArray {
        return request(
            method = "POST",
            path = "/sync/push",
            token = token,
            body = JSONObject().put("mutations", mutations)
        ).getJSONArray("results")
    }

    suspend fun uploadAttachment(token: String, file: File): JSONObject {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val mimeType = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        return request(
            method = "POST",
            path = "/attachments",
            token = token,
            body = JSONObject()
                .put("fileName", file.name)
                .put("mimeType", mimeType)
                .put("contentBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        )
    }

    suspend fun attachmentUrl(token: String, fileId: String): String = request(
        method = "GET",
        path = "/attachments/url",
        token = token,
        query = mapOf("fileID" to fileId)
    ).getString("url")

    suspend fun revokeCurrentToken(token: String) {
        request(
            method = "DELETE",
            path = "/tokens/current",
            token = token
        )
    }

    suspend fun download(url: String, destination: File) = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode !in 200..299) error("附件下载失败")
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        token: String? = null,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        check(isConfigured) { "尚未配置统一同步 API 地址" }
        val queryText = query.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val connection = URL("${BuildConfig.SYNC_API_BASE_URL.trimEnd('/')}$path$queryText")
            .openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.setRequestProperty("Accept", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            val envelope = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299 || !envelope.optBoolean("ok")) {
                error(envelope.optString("message", "同步服务请求失败"))
            }
            envelope.getJSONObject("data")
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
