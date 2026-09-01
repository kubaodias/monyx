package com.monyx.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.data.MonyxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What can be CREATED. "transfer" is deliberately absent: rows with that kind
 * may still exist from before it was removed, and every read path still renders
 * them, but nothing offers to make a new one.
 */
enum class EntryKind(val wire: String) {
    Expense("expense"),
    Income("income"),
}

data class AddUiState(
    val amount: AmountInput = AmountInput(),
    val kind: EntryKind = EntryKind.Expense,
    val categoryId: String? = null,
    val accountId: String? = null,
    val note: String = "",
    val date: LocalDate = Dates.today(),
) {
    val amountMinor: Long get() = amount.evaluate().toMinor()
    val canSave: Boolean
        get() = amountMinor > 0 && accountId != null && categoryId != null
}

class AddViewModel(private val repository: MonyxRepository) : ViewModel() {

    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state.asStateFlow()

    val expenseCategories: StateFlow<List<CategoryEntity>> = repository.expenseCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val incomeCategories: StateFlow<List<CategoryEntity>> = repository.incomeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = repository.activeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Account and date come from defaults so the fast path stays two taps. */
    fun ensureDefaultAccount(available: List<AccountEntity>) {
        if (_state.value.accountId == null && available.isNotEmpty()) {
            _state.value = _state.value.copy(accountId = available.first().id)
        }
    }

    fun onKey(action: KeyAction) {
        val current = _state.value
        val next = when (action) {
            is KeyAction.Digit -> current.amount.digit(action.value)
            KeyAction.Separator -> current.amount.separator()
            KeyAction.Backspace -> current.amount.backspace()
            is KeyAction.Operator -> current.amount.operator(action.op)
            KeyAction.Equals -> current.amount.evaluate()
        }
        _state.value = current.copy(amount = next)
    }

    fun setKind(kind: EntryKind) {
        // Expense and income categories are different lists, so a category
        // chosen under one kind is meaningless under the other.
        _state.value = _state.value.copy(kind = kind, categoryId = null)
    }

    fun selectCategory(id: String) {
        _state.value = _state.value.copy(categoryId = id)
    }

    fun selectAccount(id: String) {
        _state.value = _state.value.copy(accountId = id)
    }

    fun setNote(note: String) {
        _state.value = _state.value.copy(note = note)
    }

    fun setDate(date: LocalDate) {
        _state.value = _state.value.copy(date = date)
    }

    fun clearAmount() {
        _state.value = _state.value.copy(amount = AmountInput())
    }

    /**
     * Leaving the tab throws the half-typed entry away.
     *
     * A draft that survives is worse than no draft at all: coming back to a
     * screen already holding 47,50 against a category chosen an hour ago is how
     * a wrong amount gets saved, because the number looks like something you
     * just typed. The account is kept — it is a default, not part of the entry.
     */
    fun discardDraft() {
        _state.value = AddUiState(accountId = _state.value.accountId)
    }

    /**
     * Saves to Room and returns immediately. The user never waits on the
     * network — the row is written with pending = 1 and SyncWorker picks it up.
     */
    fun save(createdBy: String, onSaved: () -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        val accountId = current.accountId ?: return

        viewModelScope.launch {
            repository.addTransaction(
                kind = current.kind.wire,
                amountMinor = current.amountMinor,
                accountId = accountId,
                categoryId = current.categoryId,
                note = current.note,
                occurredAtMs = occurredAt(current.date),
                createdBy = createdBy,
            )
            // Reset for the next entry, keeping the account and kind the user
            // already chose — the next expense is usually like the last one.
            _state.value = AddUiState(
                kind = current.kind,
                accountId = current.accountId,
            )
            onSaved()
        }
    }

    /**
     * Midday on the chosen day when it is not today, so a date change cannot
     * land the row in a neighbouring day once the local date is computed.
     */
    private fun occurredAt(date: LocalDate): Long =
        if (date == Dates.today()) {
            System.currentTimeMillis()
        } else {
            Dates.startOfDayMillis(date) + 12 * 60 * 60 * 1000
        }
}
