package com.monio.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sessionStore by preferencesDataStore(name = "monio_session")

/**
 * The session token stays in DataStore (§9) — everything the sync protocol
 * needs to be atomic lives in Room instead.
 *
 * android:allowBackup="false" matters here: Google Auto Backup would copy
 * DataStore to the cloud and restore it onto a NEW phone, session token
 * included. Two devices would then share one `devices` row, so revoking one
 * would revoke both (§11).
 */
class Session(private val context: Context) {

    private val tokenKey = stringPreferencesKey("session_token")
    private val householdKey = stringPreferencesKey("household_id")
    private val memberKey = stringPreferencesKey("member_id")
    private val fcmKey = stringPreferencesKey("fcm_token")

    val tokenFlow: Flow<String?> = context.sessionStore.data.map { it[tokenKey] }
    val memberIdFlow: Flow<String?> = context.sessionStore.data.map { it[memberKey] }

    suspend fun token(): String? = context.sessionStore.data.first()[tokenKey]
    suspend fun memberId(): String? = context.sessionStore.data.first()[memberKey]
    suspend fun householdId(): String? = context.sessionStore.data.first()[householdKey]
    suspend fun fcmToken(): String? = context.sessionStore.data.first()[fcmKey]

    suspend fun isEnrolled(): Boolean = token() != null

    suspend fun save(token: String, householdId: String, memberId: String) {
        context.sessionStore.edit {
            it[tokenKey] = token
            it[householdKey] = householdId
            it[memberKey] = memberId
        }
    }

    suspend fun saveFcmToken(token: String) {
        context.sessionStore.edit { it[fcmKey] = token }
    }

    suspend fun clear() {
        context.sessionStore.edit { it.clear() }
    }
}
