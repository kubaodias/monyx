package com.monyx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A category with nothing attached to it yet: the identity the charts need in
 * order to show a category that cost nothing. See [MonyxDao.rootExpenseCategories].
 */
data class CategoryRef(
    val id: String,
    val name: String,
    val color: String?,
    val icon: String?,
)

/** A category slice of a month's spending, for the pie chart and the budget bars. */
data class CategorySpend(
    val categoryId: String,
    val name: String,
    val color: String?,
    val icon: String?,
    val spentMinor: Long,
)

/**
 * One category's spending in one month, for the twelve-month history.
 *
 * The same rollup [CategorySpend] does — subcategories fold into their parent —
 * with the month it happened in, so a category is one series across the window
 * rather than twelve separate answers.
 */
data class MonthlyCategorySpend(
    val period: String,
    val categoryId: String,
    val name: String,
    val color: String?,
    val spentMinor: Long,
)

/** A resolved budget for one period: the limit in effect plus the spend against it. */
data class BudgetUsage(
    val categoryId: String,
    val name: String,
    val color: String?,
    val icon: String?,
    /** Set when the budgeted category is itself a subcategory, so the row can be
     *  drawn in its parent's colour like everywhere else. */
    val parentId: String?,
    val parentColor: String?,
    val limitMinor: Long,
    val spentMinor: Long,
)

/**
 * One row of the budgets table, for the limit line on the twelve-month chart.
 *
 * Unresolved on purpose. [MonyxDao.budgetUsage] answers "the limit in effect in
 * THIS month" in SQL, but the chart asks it of twelve months at once, and twelve
 * correlated subqueries to say "the newest row at or before each period" is a
 * query nobody will be able to read in a year. The rows come out raw and
 * [com.monyx.ui.overview.limitsPerMonth] carries them forward once, in Kotlin,
 * where the rule can be unit-tested.
 *
 * Which is why [deleted] is carried rather than filtered: clearing a limit
 * writes a tombstone, and a tombstone is the newest row for that month — it has
 * to stop the inheritance, not be skipped so the limit before it lives on.
 */
data class BudgetLimit(
    val id: String,
    val period: String,
    val categoryId: String,
    /** The category the bars stack under: the parent, or the category itself. */
    val rollupId: String,
    val limitMinor: Long,
    val deleted: Int,
    val seq: Long,
)

data class MonthTotals(val incomeMinor: Long, val expenseMinor: Long)

/**
 * One day's income and expense, for the trend on the back of the balance card.
 *
 * Quiet days are simply missing from the result rather than returned as zeros —
 * gap-filling them is the chart's job, because a window of thirty days has
 * thirty columns whether or not money moved on each one.
 */
data class DailyTotals(
    val day: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
)

/**
 * How much the selected accounts' balance MOVED on one day.
 *
 * Not the same question as [DailyTotals], which is about earning and spending:
 * a transfer is neither, and it still takes money out of one account and puts it
 * into another. With every account selected the two cancel and this is the net
 * of the day; with one account selected the transfer is the whole story.
 *
 * Quiet days are missing, like the totals above — the chart fills its own gaps.
 */
data class DailyDelta(val day: String, val deltaMinor: Long)

data class AccountBalance(
    val id: String,
    val name: String,
    val icon: String?,
    val color: String?,
    val balanceMinor: Long,
    val archived: Int = 0,
)

/** A transaction joined to the names the list needs, so the UI does no lookups. */
data class TransactionListItem(
    val id: String,
    val kind: String,
    val amountMinor: Long,
    val note: String?,
    val occurredAt: Long,
    val occurredOn: String,
    val categoryId: String?,
    val accountId: String?,
    val categoryName: String?,
    val categoryIcon: String?,
    /** Already resolved: a subcategory reports its PARENT's colour unless it
     *  has been given one of its own, so a family of categories reads as one
     *  colour group wherever it is drawn. Same precedence as
     *  Palette.colorForChild — the two must agree or the same category is drawn
     *  in two colours on two screens. */
    val categoryColor: String?,
    /**
     * What to hash on when [categoryColor] comes back null — which happens only
     * when neither the category nor its parent has ever been given a colour.
     *
     * The parent's id for a subcategory, its own for a root, which is the key
     * Palette.colorForChild hashes. Without it the fallback was keyed on
     * something local to the row and one colourless family came out as several
     * unrelated colours: the picker hashed the parent, the list hashed the
     * child, and the same category was drawn in two colours on two screens.
     */
    val categoryColorKey: String?,
    val accountName: String?,
    val transferAccountName: String?,
    /** Non-null when a repeating rule wrote this row rather than a person. */
    val recurringRuleId: String?,
    val pending: Int,
    val rejected: Int,
)

/**
 * A rule joined to the names the settings list needs, plus how many
 * transactions it has actually produced — the one figure that tells a person the
 * rule is running rather than merely saved.
 */
data class RecurringRuleListItem(
    val id: String,
    val kind: String,
    val amountMinor: Long,
    val note: String?,
    val freq: String,
    val startsOn: String,
    val endsOn: String?,
    val categoryId: String?,
    val accountId: String,
    val categoryName: String?,
    val categoryIcon: String?,
    /** Already resolved to the parent's colour unless the subcategory carries
     *  one of its own, as everywhere else. */
    val categoryColor: String?,
    /**
     * What to hash on when [categoryColor] comes back null — which happens only
     * when neither the category nor its parent has ever been given a colour.
     *
     * The parent's id for a subcategory, its own for a root, which is the key
     * Palette.colorForChild hashes. Without it the fallback was keyed on
     * something local to the row and one colourless family came out as several
     * unrelated colours: the picker hashed the parent, the list hashed the
     * child, and the same category was drawn in two colours on two screens.
     */
    val categoryColorKey: String?,
    val accountName: String?,
    val generatedCount: Int,
    /** Where the household dragged it. See [RecurringRuleEntity.sortOrder]. */
    val sortOrder: Int,
    val pending: Int,
    val rejected: Int,
)

@Dao
interface MonyxDao {

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
    suspend fun upsertMonthPlans(rows: List<MonthPlanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(rows: List<MemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecurringRules(rows: List<RecurringRuleEntity>)

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

    @Query("SELECT * FROM month_plans WHERE pending = 1")
    suspend fun pendingMonthPlans(): List<MonthPlanEntity>

    @Query("SELECT * FROM recurring_rules WHERE pending = 1")
    suspend fun pendingRecurringRules(): List<RecurringRuleEntity>

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

    @Query("UPDATE month_plans SET pending = 0 WHERE id IN (:ids)")
    suspend fun clearPendingMonthPlans(ids: List<String>)

    @Query("UPDATE members SET pending = 0 WHERE id IN (:ids)")
    suspend fun clearPendingMembers(ids: List<String>)

    @Query("UPDATE recurring_rules SET pending = 0 WHERE id IN (:ids)")
    suspend fun clearPendingRecurringRules(ids: List<String>)

    @Query("UPDATE accounts SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectAccounts(ids: List<String>)

    @Query("UPDATE categories SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectCategories(ids: List<String>)

    @Query("UPDATE transactions SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectTransactions(ids: List<String>)

    @Query("UPDATE budgets SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectBudgets(ids: List<String>)

    @Query("UPDATE month_plans SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectMonthPlans(ids: List<String>)

    @Query("UPDATE members SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectMembers(ids: List<String>)

    @Query("UPDATE recurring_rules SET pending = 0, rejected = 1 WHERE id IN (:ids)")
    suspend fun rejectRecurringRules(ids: List<String>)

    /** The other half of the restore runbook in the README. */
    @Query("UPDATE accounts SET pending = 1")
    suspend fun markAllAccountsPending()

    @Query("UPDATE categories SET pending = 1")
    suspend fun markAllCategoriesPending()

    @Query("UPDATE transactions SET pending = 1")
    suspend fun markAllTransactionsPending()

    @Query("UPDATE budgets SET pending = 1")
    suspend fun markAllBudgetsPending()

    @Query("UPDATE month_plans SET pending = 1")
    suspend fun markAllMonthPlansPending()

    @Query("UPDATE recurring_rules SET pending = 1")
    suspend fun markAllRecurringRulesPending()

    @Query(
        """SELECT (SELECT COUNT(*) FROM transactions WHERE rejected = 1)
                + (SELECT COUNT(*) FROM accounts     WHERE rejected = 1)
                + (SELECT COUNT(*) FROM categories   WHERE rejected = 1)
                + (SELECT COUNT(*) FROM budgets      WHERE rejected = 1)
                + (SELECT COUNT(*) FROM month_plans  WHERE rejected = 1)
                + (SELECT COUNT(*) FROM recurring_rules WHERE rejected = 1)"""
    )
    fun rejectedCount(): Flow<Int>

    // ------------------------------------------------------------------ reads

    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY sortOrder, name")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE deleted = 0 AND kind = :kind ORDER BY sortOrder, name")
    fun categoriesOfKind(kind: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY sortOrder, name")
    fun accounts(): Flow<List<AccountEntity>>

    /** What a picker should offer. Settings wants accounts(), archive and all. */
    @Query("SELECT * FROM accounts WHERE deleted = 0 AND archived = 0 ORDER BY sortOrder, name")
    fun activeAccounts(): Flow<List<AccountEntity>>

    /**
     * Oldest first. Who joined the household when is a fact about the household;
     * alphabetical order was a fact about nothing, and it reshuffled the list
     * every time somebody was renamed.
     *
     * The phone's own member is lifted to the top on the way to the screen, not
     * here — the DAO does not know whose phone this is, and a query that took a
     * member id would make "the list of members" depend on the device reading
     * it. See MembersSection.
     */
    @Query("SELECT * FROM members WHERE deleted = 0 ORDER BY createdAt, id")
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
                       AND t.deleted = 0), 0) AS balanceMinor,
                  a.archived AS archived
           FROM accounts a WHERE a.deleted = 0 ORDER BY a.archived, a.sortOrder, a.name"""
    )
    fun accountBalances(): Flow<List<AccountBalance>>

    /**
     * The same balances, as they stood at the end of [through].
     *
     * An account's position is a running total, so asking about a past month
     * means asking what had happened by the end of it — the opening balance plus
     * everything dated on or before that day, and nothing after. This is the
     * only honest answer to "how did we stand in June": the plain balance above
     * is today's, and printing it under a month switcher set to June is the bug
     * this query exists to fix.
     *
     * Bounded on occurredOn rather than occurredAt, like every other aggregate
     * here, so an expense entered at half past midnight lands in the day the
     * household says it happened.
     */
    @Query(
        """SELECT a.id AS id, a.name AS name, a.icon AS icon, a.color AS color,
                  a.initialBalanceMinor
                  + COALESCE((SELECT SUM(CASE
                        WHEN t.kind = 'income'   THEN  t.amountMinor
                        WHEN t.kind = 'expense'  THEN -t.amountMinor
                        WHEN t.kind = 'transfer' THEN -t.amountMinor
                     END) FROM transactions t
                     WHERE t.accountId = a.id AND t.deleted = 0
                       AND t.occurredOn <= :through), 0)
                  + COALESCE((SELECT SUM(t.amountMinor) FROM transactions t
                     WHERE t.transferAccountId = a.id AND t.kind = 'transfer'
                       AND t.deleted = 0 AND t.occurredOn <= :through), 0) AS balanceMinor,
                  a.archived AS archived
           FROM accounts a WHERE a.deleted = 0 ORDER BY a.archived, a.sortOrder, a.name"""
    )
    fun accountBalancesThrough(through: String): Flow<List<AccountBalance>>

    /**
     * Every top-level spending category, whether or not anything was spent on it.
     *
     * The breakdown queries below start FROM transactions, so a category with no
     * expenses in the window has no row and vanishes from the chart's legend
     * entirely. That is wrong in both directions: a household cannot see that it
     * spent nothing on Zabawki this month, and cannot tap the category to check.
     * The callers pad the query results with these at zero.
     *
     * Top-level only, because that is what the breakdown rolls up to — a
     * subcategory is already counted inside its parent's slice.
     */
    @Query(
        """SELECT id, name, color, icon FROM categories
           WHERE deleted = 0 AND kind = 'expense' AND parentId IS NULL
           ORDER BY sortOrder, name"""
    )
    fun rootExpenseCategories(): Flow<List<CategoryRef>>

    /**
     * The overview's account filter, shared by the three queries below.
     *
     * `allAccounts = 1` means no filter, and is NOT the same as passing every
     * id: an archived account's transactions still belong in the month's totals,
     * even though the account is no longer offered as a chip. Room expands an
     * empty :accountIds to `IN ()`, which SQLite accepts and reads as false, so
     * the unfiltered call is safe without a sentinel.
     */
    @Query(
        """SELECT
             COALESCE(SUM(CASE WHEN kind = 'income'  THEN amountMinor ELSE 0 END), 0) AS incomeMinor,
             COALESCE(SUM(CASE WHEN kind = 'expense' THEN amountMinor ELSE 0 END), 0) AS expenseMinor
           FROM transactions
           WHERE deleted = 0 AND substr(occurredOn, 1, 7) = :period
             AND (:allAccounts = 1 OR accountId IN (:accountIds))"""
    )
    fun monthTotals(period: String, allAccounts: Int, accountIds: List<String>): Flow<MonthTotals>

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
             AND (:allAccounts = 1 OR t.accountId IN (:accountIds))
           GROUP BY COALESCE(p.id, c.id)
           ORDER BY spentMinor DESC"""
    )
    fun spendByCategory(period: String, allAccounts: Int, accountIds: List<String>): Flow<List<CategorySpend>>

    /**
     * The same breakdown, month by month across a range of periods.
     *
     * One query for the whole window rather than twelve for one month each:
     * the chart is a shape, and a shape assembled from twelve separately
     * arriving flows redraws twelve times and reflows its y axis on every one
     * of them.
     *
     * Months with no spending simply do not come back — SQLite cannot invent a
     * row for a month nothing happened in — so the caller fills the gaps. A bar
     * chart with the empty months missing would space August and October as
     * neighbours and read as a month that never existed.
     */
    @Query(
        """SELECT substr(t.occurredOn, 1, 7)  AS period,
                  COALESCE(p.id, c.id)        AS categoryId,
                  COALESCE(p.name, c.name)    AS name,
                  COALESCE(p.color, c.color)  AS color,
                  SUM(t.amountMinor)          AS spentMinor
           FROM transactions t
           JOIN categories c ON c.id = t.categoryId
           LEFT JOIN categories p ON p.id = c.parentId
           WHERE t.deleted = 0 AND t.kind = 'expense'
             AND substr(t.occurredOn, 1, 7) >= :fromPeriod
             AND substr(t.occurredOn, 1, 7) <= :toPeriod
             AND (:allAccounts = 1 OR t.accountId IN (:accountIds))
           GROUP BY period, COALESCE(p.id, c.id)
           ORDER BY period"""
    )
    fun spendByCategoryPerMonth(
        fromPeriod: String,
        toPeriod: String,
        allAccounts: Int,
        accountIds: List<String>,
    ): Flow<List<MonthlyCategorySpend>>

    /**
     * Income and expense per day over a date range, both ends inclusive.
     *
     * Bounded on occurredOn — the local date the client authored — and not on
     * occurredAt, so a day here is the same day the monthly aggregates bucket
     * into. A transfer is neither earned nor spent, so it is excluded by the
     * two CASEs rather than by a WHERE: it still moved on that day, it just
     * contributes nothing to either side.
     */
    @Query(
        """SELECT occurredOn AS day,
             COALESCE(SUM(CASE WHEN kind = 'income'  THEN amountMinor ELSE 0 END), 0) AS incomeMinor,
             COALESCE(SUM(CASE WHEN kind = 'expense' THEN amountMinor ELSE 0 END), 0) AS expenseMinor
           FROM transactions
           WHERE deleted = 0 AND occurredOn >= :fromDay AND occurredOn <= :toDay
             AND (:allAccounts = 1 OR accountId IN (:accountIds))
           GROUP BY occurredOn
           ORDER BY occurredOn"""
    )
    fun dailyTotals(
        fromDay: String,
        toDay: String,
        allAccounts: Int,
        accountIds: List<String>,
    ): Flow<List<DailyTotals>>

    /**
     * How far the selected accounts' balance moved on each day of a range.
     *
     * The same three signs [accountBalances] adds up — income in, expense out,
     * transfer out of the account it left — plus the fourth the totals above
     * have no place for: a transfer ARRIVING, which is a row belonging to the
     * other account. Hence the union: one row of the ledger can move two
     * accounts, and the second one is keyed by transferAccountId.
     *
     * Unfiltered, the two halves of a transfer cancel and this is simply income
     * minus expense. Under a filter it is not, and that difference is the whole
     * reason the query exists: a line drawn from earning and spending alone
     * would ignore the 2 000 zł that left the current account for the savings
     * one, and then disagree with the balance printed above it.
     *
     * "Every account" means every OPEN one here, and this is the one place it
     * does. A balance is money you can still reach, and an archived account is
     * a closed envelope: the holiday fund that paid for July is not part of
     * where the household stands in September. So unfiltered, a set-aside moved
     * INTO an archived fund is money leaving the total, and the fund's own
     * spending moves nothing — the same rule the balance above the line
     * follows, or the line would not end where the figure does.
     */
    @Query(
        """SELECT day, SUM(deltaMinor) AS deltaMinor FROM (
             SELECT occurredOn AS day,
                    CASE WHEN kind = 'income' THEN amountMinor ELSE -amountMinor END AS deltaMinor
               FROM transactions
              WHERE deleted = 0 AND occurredOn >= :fromDay AND occurredOn <= :toDay
                AND ((:allAccounts = 1 AND accountId IN
                        (SELECT id FROM accounts WHERE archived = 0 AND deleted = 0))
                     OR accountId IN (:accountIds))
             UNION ALL
             SELECT occurredOn AS day, amountMinor AS deltaMinor
               FROM transactions
              WHERE deleted = 0 AND kind = 'transfer' AND transferAccountId IS NOT NULL
                AND occurredOn >= :fromDay AND occurredOn <= :toDay
                AND ((:allAccounts = 1 AND transferAccountId IN
                        (SELECT id FROM accounts WHERE archived = 0 AND deleted = 0))
                     OR transferAccountId IN (:accountIds))
           )
           GROUP BY day ORDER BY day"""
    )
    fun dailyDeltas(
        fromDay: String,
        toDay: String,
        allAccounts: Int,
        accountIds: List<String>,
    ): Flow<List<DailyDelta>>

    /**
     * Budgets carry forward, resolved lazily at query time. This is the
     * client's own implementation of the limit-in-effect rule; the server's copy
     * lives only in the alert path, and both read the same synced rows.
     *
     * A budget includes its subcategories: a limit on Home covers Home > Repairs.
     */
    @Query(
        """SELECT b.categoryId AS categoryId, c.name AS name, c.color AS color, c.icon AS icon,
                  c.parentId AS parentId, pc.color AS parentColor,
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
           LEFT JOIN categories pc ON pc.id = c.parentId
           WHERE b.deleted = 0
             AND b.period = (SELECT MAX(b2.period) FROM budgets b2
                             WHERE b2.categoryId = b.categoryId AND b2.period <= :period)
             -- Exactly one row per category, even when two exist for the same
             -- month. That happens for real: the client writes a budget under an
             -- id derived from (category, period), but the server resolves it
             -- through ON CONFLICT (household, category, period) onto whatever
             -- row is already there, which keeps its own id. The push is
             -- reported applied, pending is cleared, and the next pull brings
             -- the canonical row back under the OTHER id — leaving two local
             -- rows for one budget, permanently.
             --
             -- The list merely looked odd with a category twice. Summing the
             -- limits to answer "how much is left to assign" makes it wrong, so
             -- it is resolved here: newest seq wins, which is the server's copy,
             -- because that is the one every other phone is looking at.
             -- pending first, and that ordering is not a detail. A row this
             -- phone has just written carries seq 0 until the server stamps
             -- it, so on seq alone the newest edit there is loses to every
             -- synced row — and where a duplicate exists, changing the limit
             -- did nothing visible at all: the dialog closed, the row kept its
             -- old number, and the only way to see the edit was to sync.
             AND b.id = (SELECT b3.id FROM budgets b3
                         WHERE b3.categoryId = b.categoryId AND b3.period = b.period
                           AND b3.deleted = 0
                         ORDER BY b3.pending DESC, b3.seq DESC, b3.id DESC LIMIT 1)
           ORDER BY c.sortOrder, c.name"""
    )
    fun budgetUsage(period: String): Flow<List<BudgetUsage>>

    /**
     * Every budget row up to [toPeriod], for the chart's limit line.
     *
     * Not bounded below: a limit set two years ago and never touched since is
     * the limit in effect this month, so cutting the rows at the window's first
     * month would lose exactly the households that budget once and leave it.
     * The table holds one row per category per CHANGE, so this is small.
     *
     * Deleted rows included — see [BudgetLimit].
     */
    @Query(
        """SELECT b.id AS id, b.period AS period, b.categoryId AS categoryId,
                  COALESCE(c.parentId, c.id) AS rollupId,
                  b.limitMinor AS limitMinor, b.deleted AS deleted, b.seq AS seq
           FROM budgets b
           JOIN categories c ON c.id = b.categoryId AND c.deleted = 0
           WHERE b.period <= :toPeriod
           ORDER BY b.period, b.seq, b.id"""
    )
    fun budgetLimitsThrough(toPeriod: String): Flow<List<BudgetLimit>>

    @Query(
        """SELECT b.* FROM budgets b
           WHERE b.categoryId = :categoryId AND b.deleted = 0
             AND b.period = (SELECT MAX(b2.period) FROM budgets b2
                             WHERE b2.categoryId = :categoryId AND b2.period <= :period)"""
    )
    suspend fun effectiveBudget(categoryId: String, period: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId AND period = :period")
    suspend fun budgetForPeriod(categoryId: String, period: String): BudgetEntity?

    // ------------------------------------------------------------ the plan

    @Query("SELECT * FROM month_plans WHERE period = :period AND deleted = 0")
    fun monthPlan(period: String): Flow<MonthPlanEntity?>

    @Query("SELECT * FROM month_plans WHERE period = :period")
    suspend fun monthPlanRow(period: String): MonthPlanEntity?

    /**
     * What a month with no plan of its own should offer as a starting figure:
     * the most recent earlier plan. Planning a month usually means repeating
     * the last one with a nudge, not starting from a blank field.
     */
    @Query(
        """SELECT plannedMinor FROM month_plans
           WHERE deleted = 0 AND period < :period
           ORDER BY period DESC LIMIT 1"""
    )
    suspend fun previousPlannedMinor(period: String): Long?

    /** Income actually recorded in a month — the fallback suggestion. */
    @Query(
        """SELECT COALESCE(SUM(amountMinor), 0) FROM transactions
           WHERE deleted = 0 AND kind = 'income'
             AND substr(occurredOn, 1, 7) = :period"""
    )
    suspend fun incomeMinorIn(period: String): Long

    /**
     * Search is a plain LIKE, never FTS5: a database containing virtual tables
     * cannot be exported at all, which would silently cost us the backup path.
     * At 36k rows a LIKE scan is a few milliseconds.
     */
    @Query(
        """SELECT t.id, t.kind, t.amountMinor, t.note, t.occurredAt, t.occurredOn,
                  t.categoryId, t.accountId,
                  c.name AS categoryName, c.icon AS categoryIcon,
                  COALESCE(c.color, pc.color) AS categoryColor,
                  COALESCE(c.parentId, c.id) AS categoryColorKey,
                  a.name AS accountName, ta.name AS transferAccountName,
                  t.recurringRuleId, t.pending, t.rejected
           FROM transactions t
           LEFT JOIN categories c ON c.id = t.categoryId
           LEFT JOIN categories pc ON pc.id = c.parentId
           LEFT JOIN accounts   a ON a.id = t.accountId
           LEFT JOIN accounts  ta ON ta.id = t.transferAccountId
           WHERE t.deleted = 0
             AND (:query = '' OR t.note LIKE '%' || :query || '%' OR c.name LIKE '%' || :query || '%')
             AND (:categoryId IS NULL OR t.categoryId = :categoryId OR c.parentId = :categoryId)
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
                  t.categoryId, t.accountId,
                  c.name AS categoryName, c.icon AS categoryIcon,
                  COALESCE(c.color, pc.color) AS categoryColor,
                  COALESCE(c.parentId, c.id) AS categoryColorKey,
                  a.name AS accountName, ta.name AS transferAccountName,
                  t.recurringRuleId, t.pending, t.rejected
           FROM transactions t
           LEFT JOIN categories c ON c.id = t.categoryId
           LEFT JOIN categories pc ON pc.id = c.parentId
           LEFT JOIN accounts   a ON a.id = t.accountId
           LEFT JOIN accounts  ta ON ta.id = t.transferAccountId
           WHERE t.deleted = 0
             AND (:period = '' OR substr(t.occurredOn, 1, 7) = :period)
             AND (:allAccounts = 1 OR t.accountId IN (:accountIds))
           ORDER BY t.occurredAt DESC, t.id DESC LIMIT :limit"""
    )
    fun recentTransactions(
        period: String,
        limit: Int,
        allAccounts: Int,
        accountIds: List<String>,
    ): Flow<List<TransactionListItem>>

    // ------------------------------------------------------- recurring rules

    @Query(
        """SELECT r.id, r.kind, r.amountMinor, r.note, r.freq, r.startsOn, r.endsOn,
                  r.categoryId, r.accountId,
                  c.name AS categoryName, c.icon AS categoryIcon,
                  COALESCE(c.color, pc.color) AS categoryColor,
                  COALESCE(c.parentId, c.id) AS categoryColorKey,
                  a.name AS accountName,
                  (SELECT COUNT(*) FROM transactions t
                    WHERE t.recurringRuleId = r.id AND t.deleted = 0) AS generatedCount,
                  r.sortOrder, r.pending, r.rejected
           FROM recurring_rules r
           LEFT JOIN categories c ON c.id = r.categoryId
           LEFT JOIN categories pc ON pc.id = c.parentId
           LEFT JOIN accounts   a ON a.id = r.accountId
           WHERE r.deleted = 0
           -- sortOrder first, then the old order as the tie-break. Every rule
           -- written before the column existed carries 0, so a household that
           -- has never dragged anything sees exactly the list it saw before.
           ORDER BY r.sortOrder, r.startsOn, r.id"""
    )
    fun recurringRules(): Flow<List<RecurringRuleListItem>>

    @Query("SELECT * FROM recurring_rules WHERE deleted = 0")
    suspend fun activeRecurringRules(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun recurringRule(id: String): RecurringRuleEntity?

    /**
     * Which of these transaction ids the phone already holds — in ANY state,
     * tombstones included.
     *
     * Deliberately no `deleted = 0`. A generated expense the user deleted is
     * still an occurrence that happened, and filtering tombstones out here would
     * have the next materialisation pass write it straight back.
     */
    @Query("SELECT id FROM transactions WHERE id IN (:ids)")
    suspend fun existingTransactionIds(ids: List<String>): List<String>
}
