package com.monyx.ui.transactions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.data.MonyxRepository
import com.monyx.data.Planned
import com.monyx.data.TransactionEntity
import com.monyx.data.TransactionListItem
import com.monyx.sync.SyncWorker
import com.monyx.ui.SelectedMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Transactions screen: chronological list, plain-LIKE
 * search, and filter by category / account / period. Filters live here so the
 * search field and the filter chips share one source of truth and the query
 * to Room stays a single flatMapLatest — never four separate collections
 * racing each other.
 */
class TransactionsViewModel(
    private val repository: MonyxRepository,
    private val appContext: Context,
    private val selectedMonth: SelectedMonth,
    initialCategoryId: String? = null,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _categoryId = MutableStateFlow(initialCategoryId)
    val categoryId: StateFlow<String?> = _categoryId.asStateFlow()

    private val _accountId = MutableStateFlow<String?>(null)
    val accountId: StateFlow<String?> = _accountId.asStateFlow()

    /**
     * The month on screen. Never blank, and not this screen's to own.
     *
     * The repository still understands "" as every month there has ever been,
     * and the search would arguably be better across all of it — but a switcher
     * that reads "All time" between two month arrows is a control describing a
     * state it cannot step back to, and this tab is scoped the way Overview and
     * Budget are scoped. The same month as them, in fact — see [SelectedMonth].
     */
    private val _period = selectedMonth.period
    val period: StateFlow<String> = _period

    val categories: StateFlow<List<CategoryEntity>> = repository.categories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = repository.accounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether the list also shows what the repeating rules are going to write.
     *
     * Off, always, on arrival — and deliberately not remembered. This tab is
     * where the household checks what it actually spent, and a projection left
     * switched on from three days ago would put money in that list that has not
     * moved. Turning it on is a question ("what is still coming?"), and a
     * question is asked, not left standing.
     */
    private val _includePlanned = MutableStateFlow(false)
    val includePlanned: StateFlow<Boolean> = _includePlanned.asStateFlow()

    /**
     * Whether the projection means anything for the month on screen: this one or
     * the next, per [Planned]. The chip is disabled rather than hidden when it
     * does not — a control that vanishes as you step through months reads as a
     * glitch, and one that greys out says which months it is for.
     */
    val plannedAvailable: StateFlow<Boolean> = _period
        .map { Planned.isAvailable(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val listing: StateFlow<Listing> =
        combine(_query, _categoryId, _accountId, _period, _includePlanned, ::Filters)
            .flatMapLatest { f ->
                val real = repository.transactions(
                    query = f.query,
                    categoryId = f.categoryId,
                    accountId = f.accountId,
                    period = f.period,
                )
                if (!f.includePlanned) {
                    real.map { Listing(it, emptySet()) }
                } else {
                    // The rules flow, not a one-shot read: editing a rule in
                    // Settings has to move the projection under this list, and a
                    // suspend call inside flatMapLatest would freeze it at
                    // whatever the rules were when the filter last changed.
                    combine(real, repository.recurringRules()) { rows, rules ->
                        val planned = Planned.forPeriod(
                            rules = rules,
                            period = f.period,
                            today = Dates.today(),
                            query = f.query,
                            categoryId = f.categoryId,
                            accountId = f.accountId,
                        )
                        Listing(
                            // Merged and re-sorted as one list, because the
                            // screen groups by day: leaving the projected rows
                            // in a block at the end would give the same date two
                            // separate day headers.
                            rows = (rows + planned).sortedWith(
                                compareByDescending<TransactionListItem> { it.occurredAt }
                                    .thenByDescending { it.id },
                            ),
                            plannedIds = planned.mapTo(HashSet()) { it.id },
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Listing())

    val transactions: StateFlow<List<TransactionListItem>> = listing
        .map { it.rows }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Which of [transactions] are projections rather than records.
     *
     * A set of ids beside the list, rather than a flag on the row, because the
     * row is a Room projection shared with two other screens — adding a field
     * that only one caller can ever set would make every other construction site
     * answer a question it has no business being asked.
     */
    val plannedIds: StateFlow<Set<String>> = listing
        .map { it.plannedIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private data class Listing(
        val rows: List<TransactionListItem> = emptyList(),
        val plannedIds: Set<String> = emptySet(),
    )

    private data class Filters(
        val query: String,
        val categoryId: String?,
        val accountId: String?,
        val period: String,
        val includePlanned: Boolean,
    )

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setCategoryFilter(id: String?) {
        _categoryId.value = id
    }

    fun setAccountFilter(id: String?) {
        _accountId.value = id
    }

    fun setIncludePlanned(on: Boolean) {
        _includePlanned.value = on
    }

    fun setPeriod(period: String) {
        selectedMonth.set(period)
    }

    /**
     * The filter the navigator wants shown, applied to the live ViewModel.
     *
     * The transactions tab keeps its ViewModel across a tab switch, so a jump
     * in from a budget row or a pie slice cannot pass its filter through the
     * constructor — by then the ViewModel already exists.
     *
     * A null period leaves the month ALONE. It used to snap back to the present
     * one, which was defensible while every tab held its own month and was a
     * bug the moment they started sharing: tapping the tab after choosing March
     * on the overview would have thrown March away on arrival.
     */
    fun applyFilter(categoryId: String?, period: String?) {
        _categoryId.value = categoryId
        selectedMonth.set(period)
    }

    /**
     * The month is deliberately left alone. It is the scope of the screen, the
     * way it is on Overview and Budget — not one of the things "Clear filters"
     * is offering to undo.
     */
    fun clearFilters() {
        _query.value = ""
        _categoryId.value = null
        _accountId.value = null
        // Planned rows go with them. It is not a filter in the narrowing sense —
        // it ADDS rows — but "Clear filters" means "show me the plain list of
        // what happened", and leaving projections in it would not be that.
        _includePlanned.value = false
    }

    /**
     * A local write returns immediately — the row is tombstoned on device
     * and the sheet can close right away. SyncWorker carries the tombstone to
     * the server on its own schedule.
     */
    fun deleteTransaction(id: String) {
        viewModelScope.launch {
            repository.deleteTransaction(id)
            SyncWorker.enqueue(appContext)
        }
    }

    /** Loads the full row behind a list item, which the list projection does not carry. */
    suspend fun load(id: String): TransactionEntity? = repository.transaction(id)

    /** Through [MonyxRepository.applyEdit], which is where the fold lives now
     *  that Overview and the voice summary make the same write. */
    fun saveEdit(
        original: TransactionEntity,
        amountMinor: Long,
        categoryId: String?,
        accountId: String,
        note: String,
        occurredAtMs: Long,
    ) {
        viewModelScope.launch {
            repository.updateTransaction(
                MonyxRepository.applyEdit(
                    original = original,
                    amountMinor = amountMinor,
                    categoryId = categoryId,
                    accountId = accountId,
                    note = note,
                    occurredAtMs = occurredAtMs,
                ),
            )
            SyncWorker.enqueue(appContext)
        }
    }
}
