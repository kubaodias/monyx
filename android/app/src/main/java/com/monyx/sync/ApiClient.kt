package com.monyx.sync

import com.monyx.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    val BASE_URL: String = BuildConfig.API_BASE_URL

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

    /**
     * The same connection pool, on a much shorter leash.
     *
     * Every other call here is something the household is waiting for and would
     * rather have slowly than not at all. The note is the opposite: the row is
     * already written and the summary is already on screen, and an answer that
     * arrives after the sheet has gone is worse than no answer. Five seconds is
     * about as long as a summary stays on screen.
     */
    private val briefClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val downloadClient = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
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

    /** Whether a newer release is published. `update` is null when this is the newest. */
    fun latestRelease(token: String, versionCode: Int): LatestReleaseResponse =
        json.decodeFromString(get("/app/latest?version_code=$versionCode", token))

    /**
     * A five-minute URL to one release's APK. Asked for when somebody taps
     * Update, never at launch, so the window starts when the download does.
     */
    fun releaseDownload(token: String, versionCode: Int): ReleaseDownload =
        json.decodeFromString(get("/app/download?version_code=$versionCode", token))

    /**
     * Streams a presigned URL into [target], reporting bytes as they land.
     *
     * No bearer header: the URL carries its own token, and the storage host is not
     * ours to hand a session to. A longer read timeout than the API calls, since
     * a stalled mobile connection mid-APK is ordinary.
     */
    fun download(url: String, target: java.io.File, onBytes: (Long) -> Unit) {
        val request = Request.Builder().url(url).get().build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "download_http_${response.code}")
            val body = response.body ?: throw ApiException(response.code, "download_empty")
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onBytes(total)
                    }
                }
            }
        }
    }

    /**
     * A second opinion on a spoken note, or null.
     *
     * The one call in this object that never throws. Every other endpoint here
     * reports failure because the household can act on it — retry, check the
     * code, look at the sync banner. There is nothing to act on here: the
     * transaction is saved, the note it already has is a decent one, and the
     * only thing an exception could do is travel somewhere that has to remember
     * to swallow it. Offline, rate-limited, timed out and "the server has no
     * API key configured" all arrive as the same null.
     */
    fun suggestNote(
        token: String,
        transcript: String,
        amountMinor: Long,
        category: String?,
        note: String,
    ): String? {
        val body = buildJsonObject {
            put("transcript", transcript)
            put("amount_minor", amountMinor)
            category?.let { put("category", it) }
            put("note", note)
        }
        val request = Request.Builder()
            .url("$BASE_URL/voice/note")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            briefClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val parsed = json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                (parsed?.get("note") as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        }.getOrNull()
    }

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

@Serializable
data class ReleaseNote(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    val notes: String,
    @SerialName("published_at") val publishedAt: Long,
)

@Serializable
data class AvailableUpdate(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    /** Every release newer than this phone's, newest first. */
    val notes: List<ReleaseNote> = emptyList(),
)

@Serializable
data class LatestReleaseResponse(val update: AvailableUpdate? = null)

@Serializable
data class ReleaseDownload(
    val url: String,
    @SerialName("expires_at") val expiresAt: Long,
)
