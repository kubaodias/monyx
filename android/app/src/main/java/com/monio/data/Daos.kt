package com.monio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A category slice of a month's spending, for the pie chart and the budget bars. */
data class CategorySpend(
    val categoryId: String,
    val name: String,
    val color: String?,
    val icon: String?,
    val spentMinor: Long,
)

/** A resolved budget for one period: the limit in effect plus the spend against it. */
data class BudgetUsage(
    val categoryId: String,
    val name: String,
    val color: String?,
    val icon: String?,
    val limitMinor: Long,
    val spentMinor: Long,
)

data class MonthTotals(val incomeMinor: Long, val expenseMinor: Long)

data class AccountBalance(
    val id: String,
    val name: String,
    val icon: String?,
    val color: String?,
    val balanceMinor: Long,
)

/** A transaction joined to the names the list needs, so the UI does no lookups. */
data class TransactionListItem(
    val id: String,
    val kind: String,
    val amountMinor: Long,
    val note: String?,
    val occurredAt: Long,
    val occurredOn: String,
    val categoryName: String?,
    val categoryIcon: String?,
    val categoryColor: String?,
    val accountName: String?,
    val transferAccountName: String?,
    val pending: Int,
    val rejected: Int,
)

@Dao
interface MonioDao {

    // ---------------------------------------------------------------- writes

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(rows: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(rows: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactions(rows: List<TransactionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudgets(rows: List<BudgetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(rows: List<MemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)

    // ------------------------------------------------------------ sync state

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun syncState(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun syncStateFlow(): Flow<SyncStateEntity?>

    // --------------------------------------------------------- pending rows
    // Sent in dependency order: members, accounts, categories, budgets,
    // transactions. Otherwise a new category and a transaction in it can arrive
    // in the wrong order and a real expense is lost.

    @Query("SELECT * FROM members WHERE pending = 1")
    suspend fun pendingMembers(): List<MemberEntity>

    @Query("SELECT * FROM accounts WHERE pending = 1")
    suspend fun pendingAccounts(): List<AccountEntity>

    @Query("SELECT * FROM categories WHERE pending = 1")
    suspend fun pendingCategories(): List<CategoryEntity>

    @Query("SELECT * FROM budgets WHERE pending = 1")
    suspend fun pendingBudgets(): List<BudgetEntity>

    @Query("SELECT * FROM transactions WHERE pending = 1")
    suspend fun pendingTransactions(): List<TransactionEntity>

    /**
     * Clearing pending is not optional on a rejected row — leaving it set would
     * re-push the same invalid row every hour forever.
     */
    @Query("UPDATE accounts SET pending = 0 WHERE id IN (:ids)")
    suspend fun clearPendingAccounts(ids: List<String>)

    @Query("UPDATE categories SET pending = 0 WHERE id IN (:ids)")
    suspend fun clearPendingCategories(ids: List<String>)

    @Query("UPDATE transactions SET pending = 0 WHERE id IN (:ids)")
    suspend fun clearPendingTransactions(ids: List<String>)

    @Query("UPDATE budgets SET pending = 0 WHERE id IN (:ids)")
    suspend fun clearPendingBudgets(ids: List<String>)

    @Query("UPDATE members SET pending = 0 WHERE id IN (:ids)")
    suspend fun clearPendingMembers(ids: List<String>)

    @Query("UPDATE accounts SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectAccounts(ids: List<String>)

    @Query("UPDATE categories SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectCategories(ids: List<String>)

    @Query("UPDATE transactions SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectTransactions(ids: List<String>)

    @Query("UPDATE budgets SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectBudgets(ids: List<String>)

    @Query("UPDATE members SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectMembers(ids: List<String>)

    /** The other half of the restore runbook in the README. */
    @Query("UPDATE accounts SET pending = 1")
    suspend fun markAllAccountsPending()

    @Query("UPDATE categories SET pending = 1")
    suspend fun markAllCategoriesPending()

    @Query("UPDATE transactions SET pending = 1")
    suspend fun markAllTransactionsPending()

    @Query("UPDATE budgets SET pending = 1")
    suspend fun markAllBudgetsPending()

    @Query(
        """SELECT (SELECT COUNT(*) FROM transactions WHERE rejected = 1)
                + (SELECT COUNT(*) FROM accounts     WHERE rejected = 1)
                + (SELECT COUNT(*) FROM categories   WHERE rejected = 1)
                + (SELECT COUNT(*) FROM budgets      WHERE rejected = 1)"""
    )
    fun rejectedCount(): Flow<Int>

    // ------------------------------------------------------------------ reads

    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY sortOrder, name")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE deleted = 0 AND kind = :kind ORDER BY sortOrder, name")
    fun categoriesOfKind(kind: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY sortOrder, name")
    fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM members WHERE deleted = 0 ORDER BY name")
    fun members(): Flow<List<MemberEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun category(id: String): CategoryEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transaction(id: String): TransactionEntity?

    /**
     * An account balance is its opening balance plus income, minus expenses,
     * plus or minus transfers.
     */
    @Query(
        """SELECT a.id AS id, a.name AS name, a.icon AS icon, a.color AS color,
                  a.initialBalanceMinor
                  + COALESCE((SELECT SUM(CASE
                        WHEN t.kind = 'income'   THEN  t.amountMinor
                        WHEN t.kind = 'expense'  THEN -t.amountMinor
                        WHEN t.kind = 'transfer' THEN -t.amountMinor
                     END) FROM transactions t
                     WHERE t.accountId = a.id AND t.deleted = 0), 0)
                  + COALESCE((SELECT SUM(t.amountMinor) FROM transactions t
                     WHERE t.transferAccountId = a.id AND t.kind = 'transfer'
                       AND t.deleted = 0), 0) AS balanceMinor
           FROM accounts a WHERE a.deleted = 0 ORDER BY a.sortOrder, a.name"""
    )
    fun accountBalances(): Flow<List<AccountBalance>>

    @Query(
        """SELECT
             COALESCE(SUM(CASE WHEN kind = 'income'  THEN amountMinor ELSE 0 END), 0) AS incomeMinor,
             COALESCE(SUM(CASE WHEN kind = 'expense' THEN amountMinor ELSE 0 END), 0) AS expenseMinor
           FROM transactions
           WHERE deleted = 0 AND substr(occurredOn, 1, 7) = :period"""
    )
    fun monthTotals(period: String): Flow<MonthTotals>

    /**
     * The spending breakdown. A transfer moves money, it does not spend it, so
     * it never enters spending statistics — excluded here at the query level
     * rather than left to the caller.
     *
     * Rolled up to the top-level category so the chart has one slice per parent.
     */
    @Query(
        """SELECT COALESCE(p.id, c.id)     AS categoryId,
                  COALESCE(p.name, c.name) AS name,
                  COALESCE(p.color, c.color) AS color,
                  COALESCE(p.icon, c.icon)   AS icon,
                  SUM(t.amountMinor)       AS spentMinor
           FROM transactions t
           JOIN categories c ON c.id = t.categoryId
           LEFT JOIN categories p ON p.id = c.parentId
           WHERE t.deleted = 0 AND t.kind = 'expense'
             AND substr(t.occurredOn, 1, 7) = :period
           GROUP BY COALESCE(p.id, c.id)
           ORDER BY spentMinor DESC"""
    )
    fun spendByCategory(period: String): Flow<List<CategorySpend>>

    /**
     * Budgets carry forward, resolved lazily at query time. This is the
     * client's own implementation of the limit-in-effect rule; the server's copy
     * lives only in the alert path, and both read the same synced rows.
     *
     * A budget includes its subcategories: a limit on Home covers Home > Repairs.
     */
    @Query(
        """SELECT b.categoryId AS categoryId, c.name AS name, c.color AS color, c.icon AS icon,
                  b.limitMinor AS limitMinor,
                  COALESCE((SELECT SUM(t.amountMinor) FROM transactions t
                            WHERE t.deleted = 0 AND t.kind = 'expense'
                              AND substr(t.occurredOn, 1, 7) = :period
                              AND (t.categoryId = b.categoryId
                                   OR t.categoryId IN (SELECT sc.id FROM categories sc
                                                       WHERE sc.parentId = b.categoryId
                                                         AND sc.deleted = 0))), 0) AS spentMinor
           FROM budgets b
           JOIN categories c ON c.id = b.categoryId AND c.deleted = 0
           WHERE b.deleted = 0
             AND b.period = (SELECT MAX(b2.period) FROM budgets b2
                             WHERE b2.categoryId = b.categoryId AND b2.period <= :period)
           ORDER BY c.sortOrder, c.name"""
    )
    fun budgetUsage(period: String): Flow<List<BudgetUsage>>

    @Query(
        """SELECT b.* FROM budgets b
           WHERE b.categoryId = :categoryId AND b.deleted = 0
             AND b.period = (SELECT MAX(b2.period) FROM budgets b2
                             WHERE b2.categoryId = :categoryId AND b2.period <= :period)"""
    )
    suspend fun effectiveBudget(categoryId: String, period: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId AND period = :period")
    suspend fun budgetForPeriod(categoryId: String, period: String): BudgetEntity?

    /**
     * Search is a plain LIKE, never FTS5: a database containing virtual tables
     * cannot be exported at all, which would silently cost us the backup path.
     * At 36k rows a LIKE scan is a few milliseconds.
     */
    @Query(
        """SELECT t.id, t.kind, t.amountMinor, t.note, t.occurredAt, t.occurredOn,
                  c.name AS categoryName, c.icon AS categoryIcon, c.color AS categoryColor,
                  a.name AS accountName, ta.name AS transferAccountName,
                  t.pending, t.rejected
           FROM transactions t
           LEFT JOIN categories c ON c.id = t.categoryId
           LEFT JOIN accounts   a ON a.id = t.accountId
           LEFT JOIN accounts  ta ON ta.id = t.transferAccountId
           WHERE t.deleted = 0
             AND (:query = '' OR t.note LIKE '%' || :query || '%' OR c.name LIKE '%' || :query || '%')
             AND (:categoryId IS NULL OR t.categoryId = :categoryId)
             AND (:accountId  IS NULL OR t.accountId  = :accountId)
             AND (:period = '' OR substr(t.occurredOn, 1, 7) = :period)
           ORDER BY t.occurredAt DESC, t.id DESC"""
    )
    fun transactions(
        query: String,
        categoryId: String?,
        accountId: String?,
        period: String,
    ): Flow<List<TransactionListItem>>

    @Query(
        """SELECT t.id, t.kind, t.amountMinor, t.note, t.occurredAt, t.occurredOn,
                  c.name AS categoryName, c.icon AS categoryIcon, c.color AS categoryColor,
                  a.name AS accountName, ta.name AS transferAccountName,
                  t.pending, t.rejected
           FROM transactions t
           LEFT JOIN categories c ON c.id = t.categoryId
           LEFT JOIN accounts   a ON a.id = t.accountId
           LEFT JOIN accounts  ta ON ta.id = t.transferAccountId
           WHERE t.deleted = 0
           ORDER BY t.occurredAt DESC, t.id DESC LIMIT :limit"""
    )
    fun recentTransactions(limit: Int): Flow<List<TransactionListItem>>
}
