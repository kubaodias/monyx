package com.monio.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One schema shared by the SQL DB and Room — identical structure on both sides
 * reduces sync to copying rows.
 *
 * Room carries two extra columns on every synced table: `pending` and
 * `rejected`. A local write sets pending = 1 and returns to the UI immediately —
 * the user never waits on the network. A row the server refuses has pending
 * cleared and rejected set, so it stops being retried but never disappears.
 *
 * Room does NOT declare foreign keys. The server enforces them, which is where
 * they belong; on the client they would only produce constraint violations when
 * a page boundary delivers a transaction before its category. The client is a
 * cache. Keep the indices, drop the constraints.
 */

@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val seq: Long = 0,
    val deleted: Int = 0,
    val pending: Int = 0,
    val rejected: Int = 0,
)

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String? = null,
    val color: String? = null,
    /** Money is an integer in minor units, always grosze. Never a float. */
    val initialBalanceMinor: Long = 0,
    val sortOrder: Int = 0,
    val seq: Long = 0,
    val deleted: Int = 0,
    val pending: Int = 0,
    val rejected: Int = 0,
)

@Entity(tableName = "categories", indices = [Index("parentId")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
    val icon: String? = null,
    val color: String? = null,
    /** 'expense' or 'income'. */
    val kind: String,
    val sortOrder: Int = 0,
    val seq: Long = 0,
    val deleted: Int = 0,
    val pending: Int = 0,
    val rejected: Int = 0,
)

@Entity(
    tableName = "transactions",
    indices = [Index("occurredOn"), Index("categoryId"), Index("accountId"), Index("seq")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    /** 'expense', 'income' or 'transfer'. Amounts are always positive; the
     *  direction comes from kind. */
    val kind: String,
    val amountMinor: Long,
    val accountId: String,
    val transferAccountId: String? = null,
    val categoryId: String? = null,
    val note: String? = null,
    /** Epoch ms, for ordering. */
    val occurredAt: Long,
    /** 'YYYY-MM-DD' local date, for bucketing. One column removes the entire
     *  timezone class of bugs. */
    val occurredOn: String,
    val createdBy: String,
    val source: String = "manual",
    val createdAt: Long,
    val seq: Long = 0,
    val deleted: Int = 0,
    val pending: Int = 0,
    val rejected: Int = 0,
)

@Entity(tableName = "budgets", indices = [Index(value = ["categoryId", "period"])])
data class BudgetEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    /** 'YYYY-MM'. A budgets row is a limit that holds from its period onward
     *  until a newer row supersedes it. */
    val period: String,
    val limitMinor: Long,
    val seq: Long = 0,
    val deleted: Int = 0,
    val pending: Int = 0,
    val rejected: Int = 0,
)

/**
 * The cursor and epoch live in a one-row Room table, not DataStore, and this is
 * a deliberate trick.
 *
 * Room verifies a schema hash on open and throws the first time an entity
 * changes without a version bump. The obvious fix, fallbackToDestructiveMigration(),
 * silently wipes the device database — which is exactly the second copy the
 * restore runbook depends on. But because the cursor lives INSIDE that
 * database, a destructive wipe also resets it to zero, the next sync re-pulls
 * everything, and
 * destructive migration becomes a legitimate strategy rather than a data-loss
 * bug.
 *
 * Persisting the cursor in the same transaction that applies a page is also what
 * makes "advance the cursor only after the page is applied" genuinely atomic,
 * which it could never be split across DataStore and Room.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val cursor: Long = 0,
    val epoch: Long = 0,
    val lastSyncAt: Long = 0,
    val lastBackupAt: Long = 0,
)
