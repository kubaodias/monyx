package com.monyx.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * About sixty lines of client. Not Retrofit: five endpoints do not justify an
 * interface-proxy layer. Not Ktor: it drags in its own engine and serialization
 * stack. Not java.net.http.HttpClient: it does not exist on Android.
 */
object Api {
    /**
     * Still the monio-api host, and deliberately so.
     *
     * A Telnyx invoke URL is minted from the function's name and id, and the
     * CLI has no rename for a function — so this string cannot follow the
     * rename until a monyx-api function exists and the data has been copied
     * across. Purging the name from a URL that is not ours yet would only
     * produce a host that resolves to nothing.
     */
    const val BASE_URL = "https://monio-api-db2fb8bb-e.telnyxcompute.com"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    class ApiException(val status: Int, val code: String) : Exception("HTTP $status: $code")

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // The server returns codes, never user-facing text; the client
                // maps them to Polish strings.
                val code = runCatching {
                    json.parseToJsonElement(body).let { (it as JsonObject)["error"]?.toString() }
                }.getOrNull()?.trim('"') ?: "http_${response.code}"
                throw ApiException(response.code, code)
            }
            return body
        }
    }

    private fun post(path: String, token: String?, body: JsonElement): String {
        val builder = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toString().toRequestBody(JSON_MEDIA))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return execute(builder.build())
    }

    private fun get(path: String, token: String): String =
        execute(
            Request.Builder()
                .url("$BASE_URL$path")
                .header("Authorization", "Bearer $token")
                .get()
                .build(),
        )

    fun enroll(inviteCode: String, memberName: String, deviceLabel: String): EnrollResponse {
        val body = buildJsonObject {
            put("invite_code", inviteCode)
            put("member_name", memberName)
            put("device_label", deviceLabel)
        }
        return json.decodeFromString(post("/auth/enroll", null, body))
    }

    fun createInvite(token: String): InviteResponse =
        json.decodeFromString(post("/invites", token, buildJsonObject {}))

    fun push(token: String, changes: List<Change>, fcmToken: String?): PushResponse {
        val payload = PushRequest(changes = changes, fcmToken = fcmToken)
        return json.decodeFromString(
            post("/sync/push", token, json.encodeToJsonElement(PushRequest.serializer(), payload)),
        )
    }

    fun pull(token: String, since: Long, limit: Int = 500): PullResponse =
        json.decodeFromString(get("/sync/pull?since=$since&limit=$limit", token))
}

@Serializable
data class EnrollResponse(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("member_id") val memberId: String,
    val epoch: Long,
    val seq: Long,
)

@Serializable
data class InviteResponse(
    val code: String,
    @SerialName("expires_at") val expiresAt: Long,
)

/** Every change is a full-row upsert; deletion is that row with deleted = 1. */
@Serializable
data class Change(val table: String, val row: JsonObject)

@Serializable
data class PushRequest(
    val changes: List<Change>,
    @SerialName("fcm_token") val fcmToken: String? = null,
)

@Serializable
data class Rejection(val table: String, val id: String? = null, val reason: String)

@Serializable
data class PushResponse(
    val seq: Long,
    val applied: Int,
    val rejected: List<Rejection> = emptyList(),
)

@Serializable
data class PullResponse(
    val changes: List<Change>,
    val epoch: Long,
    val seq: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("last_backup_at") val lastBackupAt: Long? = null,
)
