package com.monyx.sync

import androidx.room.withTransaction
import com.monyx.data.MonyxDatabase
import com.monyx.data.SyncStateEntity
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
    private val db: MonyxDatabase,
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
        val monthPlans = dao.pendingMonthPlans()
        val budgets = dao.pendingBudgets()
        val recurringRules = dao.pendingRecurringRules()
        val transactions = dao.pendingTransactions()

        val changes = buildList {
            members.forEach { add(Change("members", it.toRow())) }
            accounts.forEach { add(Change("accounts", it.toRow())) }
            categories.forEach { add(Change("categories", it.toRow())) }
            monthPlans.forEach { add(Change("month_plans", it.toRow())) }
            budgets.forEach { add(Change("budgets", it.toRow())) }
            // Before transactions: a generated row names its rule, and the
            // server's foreign key check rejects it if the rule is not there yet.
            recurringRules.forEach { add(Change("recurring_rules", it.toRow())) }
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
        val rejections = response.rejected.groupBy { it.table }
        val sentIds = sent.groupBy({ it.table }, { it.row["id"]?.toString()?.trim('"') })

        db.withTransaction {
            for ((table, ids) in sentIds) {
                val forTable = rejections[table].orEmpty()
                val bad = forTable.mapNotNull { it.id }.toSet()
                val accepted = acceptedIds(ids, forTable)
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
                    "month_plans" -> {
                        dao.clearPendingMonthPlans(accepted)
                        if (bad.isNotEmpty()) dao.rejectMonthPlans(bad.toList())
                    }
                    "budgets" -> {
                        dao.clearPendingBudgets(accepted)
                        if (bad.isNotEmpty()) dao.rejectBudgets(bad.toList())
                    }
                    "recurring_rules" -> {
                        dao.clearPendingRecurringRules(accepted)
                        if (bad.isNotEmpty()) dao.rejectRecurringRules(bad.toList())
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

    companion object {
        /**
         * Which of the ids sent for one table the server actually stored.
         *
         * A rejection with NO id names no row: the server refused the whole
         * table without parsing anything, which in practice means
         * "unknown_table" — a server older than this app. Subtracting it by id
         * removes nothing, so treating what is left as accepted clears pending
         * on rows the server never stored, and they are gone with no error
         * anywhere. Found exactly that way, an hour after a deploy: a month
         * plan sat on the phone marked synced while the server, still on the
         * previous build, had no such table.
         *
         * So a null-id rejection accepts nothing from that table and pending
         * stays set. Unlike a bad VALUE, this is transient — the rows go up on
         * their own once the server catches up, which is why they are not
         * marked rejected either.
         */
        internal fun acceptedIds(sentIds: List<String?>, rejections: List<Rejection>): List<String> {
            if (rejections.any { it.id == null }) return emptyList()
            val bad = rejections.mapNotNull { it.id }.toSet()
            return sentIds.filterNotNull().filterNot { it in bad }
        }
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
            val monthPlans = mutableListOf<JsonObject>()
            val budgets = mutableListOf<JsonObject>()
            val recurringRules = mutableListOf<JsonObject>()
            val transactions = mutableListOf<JsonObject>()

            for (change in page.changes) {
                when (change.table) {
                    "members" -> members += change.row
                    "accounts" -> accounts += change.row
                    "categories" -> categories += change.row
                    "month_plans" -> monthPlans += change.row
                    "budgets" -> budgets += change.row
                    "recurring_rules" -> recurringRules += change.row
                    "transactions" -> transactions += change.row
                }
            }

            // The client applies every pulled row EXCEPT where a local edit is
            // still pending, so an offline edit is never destroyed by a pull
            // before it has been sent.
            val pendingMembers = dao.pendingMembers().map { it.id }.toSet()
            val pendingAccounts = dao.pendingAccounts().map { it.id }.toSet()
            val pendingCategories = dao.pendingCategories().map { it.id }.toSet()
            val pendingMonthPlans = dao.pendingMonthPlans().map { it.id }.toSet()
            val pendingBudgets = dao.pendingBudgets().map { it.id }.toSet()
            val pendingRecurringRules = dao.pendingRecurringRules().map { it.id }.toSet()
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
            dao.upsertMonthPlans(
                monthPlans.map { it.toMonthPlan() }.filterNot { it.id in pendingMonthPlans },
            )
            dao.upsertBudgets(
                budgets.map { it.toBudget() }.filterNot { it.id in pendingBudgets },
            )
            dao.upsertRecurringRules(
                recurringRules.map { it.toRecurringRule() }
                    .filterNot { it.id in pendingRecurringRules },
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
