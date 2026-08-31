package com.monio.sync

import androidx.room.withTransaction
import com.monio.data.MonioDatabase
import com.monio.data.SyncStateEntity
import kotlinx.serialization.json.JsonObject

/**
 * The flow: send pending rows in dependency order through /sync/push, clear
 * flags for accepted rows and mark rejected ones, then pull in a loop until
 * has_more == false, persisting the cursor.
 *
 * Push always finishes before pull applies. This is an invariant, not an
 * implementation detail — reversing it reintroduces the lost-edit case the
 * `pending` rule exists to prevent.
 */
class SyncEngine(
    private val db: MonioDatabase,
    private val session: Session,
) {
    private val dao = db.dao()

    suspend fun sync(): Result<Unit> = runCatching {
        val token = session.token() ?: return@runCatching
        pushPending(token)
        pullAll(token)
    }

    // ------------------------------------------------------------------ push

    private suspend fun pushPending(token: String) {
        // Dependency order, not timestamp order.
        val members = dao.pendingMembers()
        val accounts = dao.pendingAccounts()
        val categories = dao.pendingCategories()
        val budgets = dao.pendingBudgets()
        val transactions = dao.pendingTransactions()

        val changes = buildList {
            members.forEach { add(Change("members", it.toRow())) }
            accounts.forEach { add(Change("accounts", it.toRow())) }
            categories.forEach { add(Change("categories", it.toRow())) }
            budgets.forEach { add(Change("budgets", it.toRow())) }
            transactions.forEach { add(Change("transactions", it.toRow())) }
        }
        if (changes.isEmpty()) return

        // Push batches are capped at 200 changes client-side. A realistic
        // push is one to five; 200 only matters on a first sync or a re-upload,
        // both of which loop anyway.
        changes.chunked(200).forEach { chunk ->
            val response = Api.push(token, chunk, session.fcmToken())
            applyPushResult(chunk, response)
        }
    }

    private suspend fun applyPushResult(sent: List<Change>, response: PushResponse) {
        val rejectedIds = response.rejected.groupBy({ it.table }, { it.id })
        val sentIds = sent.groupBy({ it.table }, { it.row["id"]?.toString()?.trim('"') })

        db.withTransaction {
            for ((table, ids) in sentIds) {
                val bad = rejectedIds[table].orEmpty().filterNotNull().toSet()
                val accepted = ids.filterNotNull().filterNot { it in bad }
                when (table) {
                    "members" -> {
                        dao.clearPendingMembers(accepted)
                        if (bad.isNotEmpty()) dao.rejectMembers(bad.toList())
                    }
                    "accounts" -> {
                        dao.clearPendingAccounts(accepted)
                        if (bad.isNotEmpty()) dao.rejectAccounts(bad.toList())
                    }
                    "categories" -> {
                        dao.clearPendingCategories(accepted)
                        if (bad.isNotEmpty()) dao.rejectCategories(bad.toList())
                    }
                    "budgets" -> {
                        dao.clearPendingBudgets(accepted)
                        if (bad.isNotEmpty()) dao.rejectBudgets(bad.toList())
                    }
                    "transactions" -> {
                        dao.clearPendingTransactions(accepted)
                        // A rejected change is never dropped and never retried:
                        // rejected = 1 AND pending cleared, in one transaction.
                        // Leaving pending set would re-push the same invalid row
                        // every hour forever.
                        if (bad.isNotEmpty()) dao.rejectTransactions(bad.toList())
                    }
                }
            }
        }
        // The seq returned by push is NOT a pull cursor. It stamps the
        // accepted local rows and nothing more; advancing the cursor to it would
        // skip every row another device committed in between — permanently, and
        // with no error. So it is deliberately unused here.
    }

    // ------------------------------------------------------------------ pull

    private suspend fun pullAll(token: String) {
        var state = dao.syncState() ?: SyncStateEntity().also { dao.upsertSyncState(it) }
        var cursor = state.cursor
        var guard = 0

        while (guard++ < 200) {
            val page = Api.pull(token, cursor)

            // If epoch differs from the stored value, the client resets its
            // cursor to zero and starts over BEFORE applying anything.
            // The epoch write and the first page's apply share one transaction —
            // otherwise a crash in between leaves a reset cursor with a stale
            // epoch, and the design would be relying on idempotent upserts to
            // cover it rather than on the rule.
            if (state.epoch != 0L && page.epoch != state.epoch) {
                cursor = 0
                val restarted = Api.pull(token, 0)
                applyPage(restarted, epochOverride = page.epoch, resetCursor = true)
                state = dao.syncState()!!
                cursor = state.cursor
                if (!restarted.hasMore) return
                continue
            }

            applyPage(page, epochOverride = page.epoch, resetCursor = false)
            state = dao.syncState()!!
            cursor = state.cursor
            if (!page.hasMore) return
        }
    }

    /**
     * A pulled page is applied inside one withTransaction { }, which also gives
     * Room's invalidation tracker a single emission instead of up to two hundred
     * recompositions. Never nest a withContext(Dispatchers.IO) inside it —
     * Room owns its own transaction dispatcher and the switch deadlocks.
     */
    private suspend fun applyPage(page: PullResponse, epochOverride: Long, resetCursor: Boolean) {
        db.withTransaction {
            // Applied in dependency order within the page.
            val members = mutableListOf<JsonObject>()
            val accounts = mutableListOf<JsonObject>()
            val categories = mutableListOf<JsonObject>()
            val budgets = mutableListOf<JsonObject>()
            val transactions = mutableListOf<JsonObject>()

            for (change in page.changes) {
                when (change.table) {
                    "members" -> members += change.row
                    "accounts" -> accounts += change.row
                    "categories" -> categories += change.row
                    "budgets" -> budgets += change.row
                    "transactions" -> transactions += change.row
                }
            }

            // The client applies every pulled row EXCEPT where a local edit is
            // still pending, so an offline edit is never destroyed by a pull
            // before it has been sent.
            val pendingMembers = dao.pendingMembers().map { it.id }.toSet()
            val pendingAccounts = dao.pendingAccounts().map { it.id }.toSet()
            val pendingCategories = dao.pendingCategories().map { it.id }.toSet()
            val pendingBudgets = dao.pendingBudgets().map { it.id }.toSet()
            val pendingTransactions = dao.pendingTransactions().map { it.id }.toSet()

            dao.upsertMembers(
                members.map { it.toMember() }.filterNot { it.id in pendingMembers },
            )
            dao.upsertAccounts(
                accounts.map { it.toAccount() }.filterNot { it.id in pendingAccounts },
            )
            dao.upsertCategories(
                categories.map { it.toCategory() }.filterNot { it.id in pendingCategories },
            )
            dao.upsertBudgets(
                budgets.map { it.toBudget() }.filterNot { it.id in pendingBudgets },
            )
            dao.upsertTransactions(
                transactions.map { it.toTransaction() }.filterNot { it.id in pendingTransactions },
            )

            val current = dao.syncState() ?: SyncStateEntity()
            // The cursor is set to the highest seq actually applied, never to
            // anything else. A page applied halfway then interrupted is harmless:
            // the cursor never moved, upserts are idempotent by id, and the
            // re-pull is a no-op.
            val highest = page.changes
                .mapNotNull { it.row["seq"]?.toString()?.trim('"')?.toLongOrNull() }
                .maxOrNull()
            val nextCursor = when {
                resetCursor -> highest ?: 0
                highest != null -> maxOf(current.cursor, highest)
                else -> current.cursor
            }
            dao.upsertSyncState(
                current.copy(
                    cursor = nextCursor,
                    epoch = epochOverride,
                    lastSyncAt = System.currentTimeMillis(),
                    lastBackupAt = page.lastBackupAt ?: current.lastBackupAt,
                ),
            )
        }
    }
}
