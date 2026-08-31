package com.monio.ui.transactions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monio.data.AccountEntity
import com.monio.data.CategoryEntity
import com.monio.data.Dates
import com.monio.data.MonioRepository
import com.monio.data.TransactionEntity
import com.monio.data.TransactionListItem
import com.monio.sync.SyncWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One selectable period filter option: "YYYY-MM" plus a Polish label to show for it. */
data class PeriodOption(val period: String, val label: String)

/**
 * Backs the Transactions screen: chronological list, plain-LIKE
 * search, and filter by category / account / period. Filters live here so the
 * search field and the filter chips share one source of truth and the query
 * to Room stays a single flatMapLatest — never four separate collections
 * racing each other.
 */
class TransactionsViewModel(
    private val repository: MonioRepository,
    private val appContext: Context,
    initialCategoryId: String? = null,
    initialPeriod: String? = null,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _categoryId = MutableStateFlow(initialCategoryId)
    val categoryId: StateFlow<String?> = _categoryId.asStateFlow()

    private val _accountId = MutableStateFlow<String?>(null)
    val accountId: StateFlow<String?> = _accountId.asStateFlow()

    /** Empty string means "all time" — matches MonioRepository.transactions' default. */
    private val _period = MutableStateFlow(initialPeriod.orEmpty())
    val period: StateFlow<String> = _period.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> = repository.categories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = repository.accounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Current month plus the five before it — enough to find anything recent
     *  without paging through every period the household has ever used. */
    val periodOptions: List<PeriodOption> = buildList {
        var p = Dates.currentPeriod()
        repeat(6) {
            add(PeriodOption(p, Dates.monthLabel(p)))
            p = Dates.shiftPeriod(p, -1)
        }
    }

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

    fun setPeriodFilter(period: String?) {
        _period.value = period.orEmpty()
    }

    /**
     * The filter the navigator wants shown, applied to the live ViewModel.
     *
     * The transactions tab keeps its ViewModel across a tab switch, so a jump
     * in from a budget row or a pie slice cannot pass its filter through the
     * constructor — by then the ViewModel already exists. Tapping the tab
     * itself sends (null, null) and lands here as a clear.
     */
    fun applyFilter(categoryId: String?, period: String?) {
        _categoryId.value = categoryId
        _period.value = period.orEmpty()
    }

    fun clearFilters() {
        _query.value = ""
        _categoryId.value = null
        _accountId.value = null
        _period.value = ""
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

    /**
     * An edit is an upsert of the whole row, exactly like a create: the sync
     * protocol carries full rows, so there is no partial-update path to get
     * wrong. occurredOn is recomputed because changing the date must move the
     * row between months, and that column is what every month query buckets on.
     */
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
                original.copy(
                    amountMinor = amountMinor,
                    categoryId = categoryId,
                    accountId = accountId,
                    note = note.ifBlank { null },
                    occurredAt = occurredAtMs,
                    occurredOn = Dates.localDate(occurredAtMs),
                ),
            )
            SyncWorker.enqueue(appContext)
        }
    }
}
