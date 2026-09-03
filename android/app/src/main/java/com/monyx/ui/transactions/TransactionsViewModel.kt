package com.monyx.ui.transactions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.MonyxRepository
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<TransactionListItem>> =
        combine(_query, _categoryId, _accountId, _period, ::Filters)
            .flatMapLatest { f ->
                repository.transactions(
                    query = f.query,
                    categoryId = f.categoryId,
                    accountId = f.accountId,
                    period = f.period,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class Filters(
        val query: String,
        val categoryId: String?,
        val accountId: String?,
        val period: String,
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
