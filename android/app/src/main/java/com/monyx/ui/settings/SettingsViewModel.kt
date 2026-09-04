package com.monyx.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monyx.MonyxApp
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.RecurringRuleListItem
import com.monyx.sync.Api
import com.monyx.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One row in the accounts list: the entity editing needs (name, opening
 * balance, icon, colour) plus the running balance from accountBalances(),
 * which the entity alone does not carry.
 */
data class AccountRow(val entity: AccountEntity, val balanceMinor: Long)

/**
 * Nesting is exactly one level deep: a root category plus its direct
 * children, never grandchildren.
 */
data class CategoryNode(val entity: CategoryEntity, val children: List<CategoryEntity>)

data class CategoryGroup(val kind: String, val roots: List<CategoryNode>)

sealed interface InviteUiState {
    data object Idle : InviteUiState
    data object Loading : InviteUiState
    data class Success(val code: String, val expiresAt: Long) : InviteUiState
    data object Error : InviteUiState
}

/**
 * Settings is where the sync design becomes operable: the rejected-row count,
 * last_backup_at from the server, and re-upload everything are not cosmetic —
 * they are the other half of the restore runbook.
 */
class SettingsViewModel(private val app: MonyxApp) : ViewModel() {

    private val repository = app.repository
    private val session = app.session

    val accountRows: Flow<List<AccountRow>> =
        combine(repository.accounts(), repository.accountBalances()) { entities, balances ->
            val balanceById = balances.associateBy { it.id }
            entities.map { entity ->
                AccountRow(entity, balanceById[entity.id]?.balanceMinor ?: entity.initialBalanceMinor)
            }
        }

    val categoryGroups: Flow<List<CategoryGroup>> = repository.categories().map { all ->
        listOf("expense", "income").map { kind ->
            val ofKind = all.filter { it.kind == kind }
            val roots = ofKind.filter { it.parentId == null }
            CategoryGroup(
                kind = kind,
                roots = roots.map { root ->
                    CategoryNode(root, ofKind.filter { it.parentId == root.id })
                },
            )
        }
    }

    val members = repository.members()

    /** Whose phone this is, for the mark beside their name in the member list. */
    val memberId: Flow<String?> = session.memberIdFlow
    val categories: Flow<List<CategoryEntity>> = repository.categories()
    val recurringRules: Flow<List<RecurringRuleListItem>> = repository.recurringRules()
    val syncState = repository.syncStateFlow()
    val rejectedCount = repository.rejectedCount()

    private val _inviteState = MutableStateFlow<InviteUiState>(InviteUiState.Idle)
    val inviteState: StateFlow<InviteUiState> = _inviteState.asStateFlow()

    private val _reuploadRequested = MutableStateFlow(false)
    val reuploadRequested: StateFlow<Boolean> = _reuploadRequested.asStateFlow()

    fun addAccount(name: String, initialBalanceMinor: Long, icon: String?, color: String?) {
        viewModelScope.launch {
            repository.addAccount(name, initialBalanceMinor, icon, color)
            SyncWorker.enqueue(app)
        }
    }

    fun updateAccount(entity: AccountEntity) {
        viewModelScope.launch {
            repository.updateAccount(entity)
            SyncWorker.enqueue(app)
        }
    }

    fun setAccountArchived(entity: AccountEntity, archived: Boolean) {
        viewModelScope.launch {
            repository.setAccountArchived(entity, archived)
            SyncWorker.enqueue(app)
        }
    }

    fun deleteAccount(entity: AccountEntity) {
        viewModelScope.launch {
            repository.deleteAccount(entity)
            SyncWorker.enqueue(app)
        }
    }

    fun addCategory(name: String, kind: String, parentId: String?, icon: String?, color: String?) {
        viewModelScope.launch {
            repository.addCategory(name, kind, parentId, icon, color)
            SyncWorker.enqueue(app)
        }
    }

    fun updateCategory(entity: CategoryEntity) {
        viewModelScope.launch {
            repository.updateCategory(entity)
            SyncWorker.enqueue(app)
        }
    }

    fun deleteCategory(entity: CategoryEntity) {
        viewModelScope.launch {
            repository.deleteCategory(entity)
            SyncWorker.enqueue(app)
        }
    }

    // ------------------------------------------------------- recurring rules

    /**
     * The rule is authored by whoever set it up, and every transaction it later
     * produces is credited to them rather than to whichever phone happened to
     * be open when it fell due — see Repository.materializeRecurring.
     */
    /**
     * The order the user dragged them into. Local write, then the usual sync —
     * order is household state, not a per-phone preference.
     */
    fun reorderAccounts(ordered: List<AccountEntity>) {
        viewModelScope.launch {
            repository.reorderAccounts(ordered)
            SyncWorker.enqueue(app)
        }
    }

    /** One list of siblings at a time. See the repository. */
    fun reorderCategories(ordered: List<CategoryEntity>) {
        viewModelScope.launch {
            repository.reorderCategories(ordered)
            SyncWorker.enqueue(app)
        }
    }

    fun addRecurringRule(draft: RuleDraft) {
        viewModelScope.launch {
            val memberId = session.memberId() ?: return@launch
            repository.addRecurringRule(
                kind = draft.kind,
                amountMinor = draft.amountMinor,
                accountId = draft.accountId,
                categoryId = draft.categoryId,
                note = draft.note,
                freq = draft.freq,
                startsOn = draft.startsOn,
                endsOn = draft.endsOn,
                createdBy = memberId,
            )
            materializeAndSync()
        }
    }

    /**
     * Editing changes what the rule will do NEXT. Transactions it has already
     * produced keep the amount they were created with, because they are a record
     * of money that moved, not a view onto the rule — the same reason a bank
     * does not restate last month's standing order when you change it.
     */
    fun updateRecurringRule(id: String, draft: RuleDraft) {
        viewModelScope.launch {
            val existing = repository.recurringRule(id) ?: return@launch
            repository.updateRecurringRule(
                existing.copy(
                    kind = draft.kind,
                    amountMinor = draft.amountMinor,
                    accountId = draft.accountId,
                    categoryId = draft.categoryId,
                    note = draft.note,
                    freq = draft.freq,
                    startsOn = draft.startsOn.toString(),
                    endsOn = draft.endsOn?.toString(),
                ),
            )
            materializeAndSync()
        }
    }

    fun deleteRecurringRule(id: String) {
        viewModelScope.launch {
            val existing = repository.recurringRule(id) ?: return@launch
            repository.deleteRecurringRule(existing)
            SyncWorker.enqueue(app)
        }
    }

    /**
     * A rule anchored today owes a transaction today, and waiting for the next
     * app open to show it would read as the rule not having worked.
     */
    private suspend fun materializeAndSync() {
        repository.materializeRecurring()
        SyncWorker.enqueue(app)
    }

    fun syncNow() {
        SyncWorker.enqueue(app)
    }

    /**
     * A network call: run off the main thread, surface a loading state, and
     * never show anything the server says verbatim — the server returns codes,
     * the client owns every user-facing string.
     */
    fun createInvite() {
        if (_inviteState.value == InviteUiState.Loading) return
        viewModelScope.launch {
            _inviteState.value = InviteUiState.Loading
            val token = session.token()
            _inviteState.value = if (token == null) {
                InviteUiState.Error
            } else {
                withContext(Dispatchers.IO) { runCatching { Api.createInvite(token) } }
                    .fold(
                        onSuccess = { InviteUiState.Success(it.code, it.expiresAt) },
                        onFailure = { InviteUiState.Error },
                    )
            }
        }
    }

    fun dismissInvite() {
        _inviteState.value = InviteUiState.Idle
    }

    /**
     * Step 3 of the restore runbook — without it, the copies of the data
     * sitting on four phones cannot be used to repair the server.
     */
    fun reuploadEverything() {
        viewModelScope.launch {
            repository.reuploadEverything()
            SyncWorker.enqueue(app)
            _reuploadRequested.value = true
        }
    }

    fun acknowledgeReupload() {
        _reuploadRequested.value = false
    }

    companion object {
        fun factory(app: MonyxApp) = viewModelFactory {
            initializer { SettingsViewModel(app) }
        }
    }
}
