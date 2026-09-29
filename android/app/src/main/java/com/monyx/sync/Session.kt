package com.monyx.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sessionStore by preferencesDataStore(name = "monyx_session")

/**
 * The session token stays in DataStore — everything the sync protocol
 * needs to be atomic lives in Room instead.
 *
 * android:allowBackup="false" matters here: Google Auto Backup would copy
 * DataStore to the cloud and restore it onto a NEW phone, session token
 * included. Two devices would then share one `devices` row, so revoking one
 * would revoke both.
 */
class Session(private val context: Context) {

    private val tokenKey = stringPreferencesKey("session_token")
    private val householdKey = stringPreferencesKey("household_id")
    private val memberKey = stringPreferencesKey("member_id")
    private val fcmKey = stringPreferencesKey("fcm_token")

    /**
     * Why the last sync failed, if it did.
     *
     * In DataStore rather than in the sync_state row, which is where it
     * belongs by rights: the database is built with
     * fallbackToDestructiveMigration, so adding a column to carry this would
     * wipe every phone's local data on upgrade — to ship a diagnostic. A
     * preference costs nothing and survives.
     *
     * Cleared on the next success, so a stale message can never be read as a
     * current fault.
     */
    private val syncErrorKey = stringPreferencesKey("last_sync_error")

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

    val syncErrorFlow: Flow<String?> = context.sessionStore.data.map { it[syncErrorKey] }

    /** [message] null after a sync that worked, which clears the last failure. */
    suspend fun recordSyncError(message: String?) {
        context.sessionStore.edit {
            if (message == null) it.remove(syncErrorKey) else it[syncErrorKey] = message
        }
    }

    suspend fun saveFcmToken(token: String) {
        context.sessionStore.edit { it[fcmKey] = token }
    }

    suspend fun clear() {
        context.sessionStore.edit { it.clear() }
    }
}
