package com.monio.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monio.data.AccountEntity
import com.monio.data.CategoryEntity
import com.monio.data.Dates
import com.monio.data.MonioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class EntryKind(val wire: String) {
    Expense("expense"),
    Income("income"),
    Transfer("transfer"),
}

data class AddUiState(
    val amount: AmountInput = AmountInput(),
    val kind: EntryKind = EntryKind.Expense,
    val categoryId: String? = null,
    val accountId: String? = null,
    val transferAccountId: String? = null,
    val note: String = "",
    val date: LocalDate = Dates.today(),
) {
    val amountMinor: Long get() = amount.evaluate().toMinor()
    val canSave: Boolean
        get() = amountMinor > 0 && accountId != null && when (kind) {
            EntryKind.Transfer -> transferAccountId != null && transferAccountId != accountId
            else -> categoryId != null
        }
}

class AddViewModel(private val repository: MonioRepository) : ViewModel() {

    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state.asStateFlow()

    val expenseCategories: StateFlow<List<CategoryEntity>> = repository.expenseCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val incomeCategories: StateFlow<List<CategoryEntity>> = repository.incomeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = repository.accounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Account and date come from defaults so the fast path stays two taps (§9). */
    fun ensureDefaultAccount(available: List<AccountEntity>) {
        if (_state.value.accountId == null && available.isNotEmpty()) {
            _state.value = _state.value.copy(accountId = available.first().id)
        }
    }

    fun onKey(action: KeyAction) {
        val current = _state.value
        val next = when (action) {
            is KeyAction.Digit -> current.amount.digit(action.value)
            KeyAction.DoubleZero -> current.amount.digit('0').digit('0')
            KeyAction.Separator -> current.amount.separator()
            KeyAction.Backspace -> current.amount.backspace()
            is KeyAction.Operator -> current.amount.operator(action.op)
            KeyAction.Confirm -> current.amount.evaluate()
        }
        _state.value = current.copy(amount = next)
    }

    fun setKind(kind: EntryKind) {
        _state.value = _state.value.copy(
            kind = kind,
            // A transfer has no category and never enters spending statistics (§6).
            categoryId = if (kind == EntryKind.Transfer) null else _state.value.categoryId,
        )
    }

    fun selectCategory(id: String) {
        _state.value = _state.value.copy(categoryId = id)
    }

    fun selectAccount(id: String) {
        _state.value = _state.value.copy(accountId = id)
    }

    fun selectTransferAccount(id: String) {
        _state.value = _state.value.copy(transferAccountId = id)
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
     * Saves to Room and returns immediately. The user never waits on the
     * network — the row is written with pending = 1 and SyncWorker picks it up
     * (§9).
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
                transferAccountId = if (current.kind == EntryKind.Transfer) {
                    current.transferAccountId
                } else {
                    null
                },
                note = current.note,
                occurredAtMs = occurredAt(current.date),
                createdBy = createdBy,
            )
            // Reset for the next entry, keeping the account and kind the user
            // already chose — the next expense is usually like the last one.
            _state.value = AddUiState(
                kind = current.kind,
                accountId = current.accountId,
                transferAccountId = current.transferAccountId,
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
