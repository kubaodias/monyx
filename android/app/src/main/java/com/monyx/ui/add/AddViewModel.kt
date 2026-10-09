package com.monyx.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Currency
import com.monyx.data.Dates
import com.monyx.data.defaultAccountId
import com.monyx.data.MonyxRepository
import com.monyx.ui.settings.RuleDraft
import com.monyx.voice.SpokenTransaction
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
    ;

    companion object {
        /**
         * Back from the stored string, falling back to an expense.
         *
         * For the shared [com.monyx.ui.add.KindSelector], which is wired on the
         * wire value because the rule editor holds one too. Anything else —
         * "transfer", or a kind a newer build invented — reads as an expense
         * rather than throwing: this is only ever called with a value the
         * selector itself put there.
         */
        fun of(wire: String): EntryKind = entries.firstOrNull { it.wire == wire } ?: Expense
    }
}

data class AddUiState(
    val amount: AmountInput = AmountInput(),
    val kind: EntryKind = EntryKind.Expense,
    val categoryId: String? = null,
    val accountId: String? = null,
    val note: String = "",
    val date: LocalDate = Dates.today(),
    /**
     * What this amount is in. Follows the chosen account unless it is changed.
     *
     * Null means "whatever the account says", which is the state a fresh entry
     * starts in: the account is picked before the keypad settles and storing a
     * copy of its currency here would go stale the moment another account is
     * chosen. [currencyOr] resolves it.
     */
    val currency: Currency? = null,
) {
    val amountMinor: Long get() = amount.evaluate().toMinor()

    /** The entry's currency, falling back to [accountCurrency]'s. */
    fun currencyOr(accountCurrency: Currency): Currency = currency ?: accountCurrency
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

    /**
     * Table order, deliberately. Ordering for display belongs to the picker —
     * see [AccountPickerDialog], which three screens share — and nothing here
     * reads this list positionally: [defaultAccountId] sorts it itself.
     */
    val accounts: StateFlow<List<AccountEntity>> = repository.activeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Account and date come from defaults so the fast path stays two taps.
     *
     * Not `available.first()`. That is first in the table, and the table sorts
     * outside-summary accounts in among the rest — so a savings account nobody
     * spends from could be what the keypad opened on, which is the one account a
     * mistyped expense must not land in. See [defaultAccount].
     */
    fun ensureDefaultAccount(available: List<AccountEntity>) {
        if (_state.value.accountId == null && available.isNotEmpty()) {
            _state.value = _state.value.copy(accountId = defaultAccountId(available))
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

    /**
     * The category the ledger was filtered to, carried over to a new entry.
     *
     * Same bargain as the account: somebody looking at one category's rows and
     * reaching for Dodaj has already said what the next one is about. The kind
     * comes with it, in the SAME write — an income category under
     * [EntryKind.Expense] is a category the grid does not list, and
     * [setKind] clears the choice it is given, so two calls would leave the
     * screen with the right kind and nothing filed under it.
     *
     * A category this screen cannot offer — deleted since the filter was set,
     * or belonging to neither list — is ignored rather than guessed at.
     */
    fun pickUpCategory(id: String) {
        val kind = kindOf(id, expenseCategories.value, incomeCategories.value) ?: return
        _state.value = _state.value.copy(kind = kind, categoryId = id)
    }

    /**
     * Picking an account also drops any currency chosen by hand.
     *
     * Switching from the złoty card to the euro one means the next thing typed
     * is almost certainly euro, and carrying over an override from the previous
     * account is how an amount ends up in a unit nobody chose for it. The
     * override is cheap to set again and expensive to not notice.
     */
    fun selectAccount(id: String) {
        _state.value = _state.value.copy(accountId = id, currency = null)
    }

    /** An explicit choice for THIS entry, overriding the account's. */
    fun selectCurrency(currency: Currency) {
        _state.value = _state.value.copy(currency = currency)
    }

    fun setNote(note: String) {
        _state.value = _state.value.copy(note = note)
    }

    fun setDate(date: LocalDate) {
        _state.value = _state.value.copy(date = date)
    }

    /**
     * What a dictated sentence got as far as, handed over to be finished by
     * hand. The parser could not reach a savable transaction and deliberately
     * wrote nothing — the second engine behind voice entry is this screen, not
     * a second parser.
     *
     * The note comes over only when it was explicitly dictated — "…, notatka
     * bilet miesięczny". The sentence itself is never the note: putting "dodaj
     * dwieście na transport" in every row pollutes the ledger and the search
     * index with the phrasing rather than with anything about the purchase.
     */
    fun prefillFromVoice(spoken: SpokenTransaction) {
        val current = _state.value
        _state.value = AddUiState(
            amount = if (spoken.amountMinor > 0) AmountInput.ofMinor(spoken.amountMinor) else AmountInput(),
            kind = spoken.kind,
            categoryId = spoken.categoryId,
            accountId = spoken.accountId ?: current.accountId,
            note = spoken.note.orEmpty(),
            date = spoken.date,
        )
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
     * just typed. The account goes back to the default with everything else —
     * see [nextEntry].
     */
    fun discardDraft() {
        _state.value = nextEntry(EntryKind.Expense, accounts.value)
    }

    /**
     * Saves to Room and returns immediately. The user never waits on the
     * network — the row is written with pending = 1 and SyncWorker picks it up.
     */
    fun save(createdBy: String, onSaved: () -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        val accountId = current.accountId ?: return
        val account = accounts.value.firstOrNull { it.id == accountId }

        viewModelScope.launch {
            repository.addTransaction(
                kind = current.kind.wire,
                amountMinor = current.amountMinor,
                accountId = accountId,
                categoryId = current.categoryId,
                note = current.note,
                occurredAtMs = occurredAt(current.date),
                createdBy = createdBy,
                currency = current.currencyOr(Currency.of(account?.currency)),
            )
            // Reset for the next entry, keeping the kind the user chose — the
            // next expense is usually like the last one. The account is not
            // kept; see [nextEntry].
            _state.value = nextEntry(current.kind, accounts.value)
            onSaved()
        }
    }

    /**
     * Turns the half-typed transaction into a repeating rule instead of a row.
     *
     * One rule, not a rule AND the transaction that seeded it. The rule's first
     * occurrence IS that transaction — materialising here rather than waiting
     * for the next app open means it lands in the ledger while the person who
     * asked for it is still looking at the screen. Materialisation is idempotent
     * (the occurrence id is derived from rule and date), so running it early
     * cannot double up with the pass that runs on the next sync.
     */
    fun saveRecurring(draft: RuleDraft, createdBy: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            repository.addRecurringRule(
                kind = draft.kind,
                amountMinor = draft.amountMinor,
                accountId = draft.accountId,
                categoryId = draft.categoryId,
                note = draft.note,
                freq = draft.freq,
                startsOn = draft.startsOn,
                endsOn = draft.endsOn,
                createdBy = createdBy,
            )
            repository.materializeRecurring()
            _state.value = nextEntry(_state.value.kind, accounts.value)
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

    companion object {
        /**
         * Which list a category id is in, or null when it is in neither.
         *
         * The two lists are the two kinds, and nothing else says which kind a
         * category is — the id arrives from another screen's filter with no
         * kind attached to it.
         */
        internal fun kindOf(
            id: String,
            expense: List<CategoryEntity>,
            income: List<CategoryEntity>,
        ): EntryKind? = when {
            expense.any { it.id == id } -> EntryKind.Expense
            income.any { it.id == id } -> EntryKind.Income
            else -> null
        }

        /**
         * The state the keypad starts the next entry in.
         *
         * The account goes back to the household's default every time, and does
         * not carry over from the last entry. Carrying it over sounds like
         * helpfulness — one purchase on a card, the next one probably on the
         * same card — but the keypad is the screen people open without reading
         * it: the account sits in a small control above the keys, it is right
         * nine times out of ten, and the tenth time the money lands somewhere
         * the person did not choose. Nothing about the screen changes to say so,
         * and a wrong account is the one mistake here that is invisible
         * afterwards — the amount and the category are still correct, so the row
         * looks fine and only two balances quietly disagree with the bank.
         *
         * An empty list gives null, which is not a problem:
         * [ensureDefaultAccount] fills it in on the next composition, and the
         * save button is off until it does.
         */
        internal fun nextEntry(kind: EntryKind, accounts: List<AccountEntity>): AddUiState =
            AddUiState(kind = kind, accountId = defaultAccountId(accounts))
    }
}
