package com.monio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monio.MonioApp
import com.monio.data.AccountEntity
import com.monio.data.CategoryEntity
import com.monio.sync.Api
import com.monio.sync.SyncWorker
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
 * Nesting is exactly one level deep (PRD §6): a root category plus its direct
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
 * they are the other half of the restore runbook (PRD §9, §10).
 */
class SettingsViewModel(private val app: MonioApp) : ViewModel() {

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

    fun syncNow() {
        SyncWorker.enqueue(app)
    }

    /**
     * A network call: run off the main thread, surface a loading state, and
     * never show anything the server says verbatim — the server returns codes,
     * the client owns every user-facing string (§9).
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
     * Step 3 of the restore runbook (§10) — without it, the copies of the data
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
        fun factory(app: MonioApp) = viewModelFactory {
            initializer { SettingsViewModel(app) }
        }
    }
}
