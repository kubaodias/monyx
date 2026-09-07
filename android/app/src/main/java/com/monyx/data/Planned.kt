package com.monyx.data

import java.time.LocalDate

/**
 * What a repeating rule is GOING to write, shown before it writes it.
 *
 * [MonyxRepository.materializeRecurring] stops at today, on purpose: a rule that
 * ran ahead of itself would put money in the ledger that has not moved, and the
 * month totals, the budget bars and the pie chart would all be describing a
 * future. That is the right default and it leaves one honest question
 * unanswered — "what is still coming this month, and what does next month
 * already owe?" — which the history tab could only answer by lying about it.
 *
 * So these rows are computed, never stored. They are the same arithmetic
 * materialisation uses, against the same [Recurrence], carrying the id the real
 * row will be given when the day arrives. Nothing here touches the database and
 * nothing here can be edited: a plan is not a transaction, and the moment it
 * becomes one it arrives through the ordinary path with the ordinary id.
 *
 * The horizon is one month past the current one, and that is a product decision
 * rather than a technical limit — [Recurrence.occurrences] would happily
 * enumerate 2029. A projection is a promise about money, and the further out it
 * reaches the more of it is fiction: a rule that ends in March, a salary that
 * changes in June. Next month is the part a household can act on.
 */
object Planned {

    /**
     * Whether [period] is close enough to project into: this month, or the next
     * one. A past month is excluded for a different reason than a distant future
     * one — there is nothing "planned" about February, only what happened.
     */
    fun isAvailable(period: String, today: LocalDate = Dates.today()): Boolean {
        val now = Dates.periodOf(today)
        return period == now || period == Dates.shiftPeriod(now, 1)
    }

    /**
     * Every occurrence falling inside [period] that has not happened yet,
     * as rows the history list can draw without knowing they are hypothetical.
     *
     * Strictly after [today], which is what keeps these from colliding with the
     * real ones: materialisation has already written every occurrence up to and
     * including today, and both sides derive the same id from (rule, date), so a
     * row that exists is never also projected.
     *
     * The three filters are applied here rather than left to the caller because
     * they have to mean exactly what the SQL means, and one of them is subtle —
     * see [matchesCategory].
     */
    fun forPeriod(
        rules: List<RecurringRuleListItem>,
        period: String,
        today: LocalDate = Dates.today(),
        query: String = "",
        categoryId: String? = null,
        accountId: String? = null,
    ): List<TransactionListItem> {
        if (!isAvailable(period, today)) return emptyList()
        val first = runCatching { Dates.firstDayOf(period) }.getOrNull() ?: return emptyList()
        val last = first.withDayOfMonth(first.lengthOfMonth())
        if (!last.isAfter(today)) return emptyList()

        return rules
            .filter { accountId == null || it.accountId == accountId }
            .filter { matchesCategory(it, categoryId) }
            .filter { matchesQuery(it, query) }
            .flatMap { rule -> occurrencesOf(rule, first, last, today).map { rule to it } }
            .map { (rule, date) -> rowFor(rule, date) }
            .sortedWith(compareByDescending<TransactionListItem> { it.occurredAt }.thenByDescending { it.id })
    }

    private fun occurrencesOf(
        rule: RecurringRuleListItem,
        first: LocalDate,
        last: LocalDate,
        today: LocalDate,
    ): List<LocalDate> {
        val anchor = runCatching { LocalDate.parse(rule.startsOn) }.getOrNull() ?: return emptyList()
        return Recurrence.occurrences(
            freq = rule.freq,
            anchor = anchor,
            endsOn = rule.endsOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            through = last,
        ).filter { !it.isBefore(first) && it.isAfter(today) }
    }

    /**
     * The chip offers root categories only, and the query it stands in for is
     * `t.categoryId = :id OR c.parentId = :id` — picking "Dom" has always
     * included "Dom > Remonty".
     *
     * `categoryColorKey` is already `COALESCE(c.parentId, c.id)`, so comparing
     * it to the chosen id says exactly that in one field: a root matches itself,
     * a subcategory matches its parent. Comparing `categoryId` alone would
     * quietly drop every subcategory from the projection while the real rows
     * beside it kept theirs.
     */
    private fun matchesCategory(rule: RecurringRuleListItem, categoryId: String?): Boolean =
        categoryId == null || rule.categoryId == categoryId || rule.categoryColorKey == categoryId

    /** Note or category name, the two columns the LIKE searches. Case-insensitive
     *  through Kotlin rather than through SQLite, which only folds ASCII and so
     *  never matched a capital Ż in the first place. */
    private fun matchesQuery(rule: RecurringRuleListItem, query: String): Boolean =
        query.isBlank() ||
            rule.note?.contains(query, ignoreCase = true) == true ||
            rule.categoryName?.contains(query, ignoreCase = true) == true

    /**
     * The id is [Recurrence.occurrenceId], not something local to this
     * projection: it is the id the row WILL have. That makes the list key stable
     * across the day it materialises — the planned row becomes the real row in
     * place rather than one disappearing and another animating in.
     */
    private fun rowFor(rule: RecurringRuleListItem, date: LocalDate): TransactionListItem =
        TransactionListItem(
            id = Recurrence.occurrenceId(rule.id, date),
            kind = rule.kind,
            amountMinor = rule.amountMinor,
            note = rule.note,
            occurredAt = Recurrence.occurredAt(date),
            occurredOn = date.toString(),
            categoryId = rule.categoryId,
            accountId = rule.accountId,
            categoryName = rule.categoryName,
            categoryIcon = rule.categoryIcon,
            categoryColor = rule.categoryColor,
            categoryColorKey = rule.categoryColorKey,
            accountName = rule.accountName,
            transferAccountName = null,
            recurringRuleId = rule.id,
            // Neither. A row that was never written cannot be waiting to sync
            // and cannot have been refused; showing the cloud badge on one would
            // be the app reporting a queue it does not have.
            pending = 0,
            rejected = 0,
        )
}
