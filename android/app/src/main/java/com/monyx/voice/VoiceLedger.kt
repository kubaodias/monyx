package com.monyx.voice

import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.MonyxRepository
import com.monyx.data.TransactionEntity
import com.monyx.ui.add.EntryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Everything the voice flow does to the database, and nothing else.
 *
 * [MonyxRepository] whole would do the job, and this exists for one reason:
 * what the ViewModel decides — save, hand off, revert, refuse — is worth
 * testing, and testing it means standing a fake in front of Room. The DAO is
 * sixty methods and there is no mocking library in this project, so the choice
 * was a narrow interface or an untested state machine that writes to a shared
 * ledger. It also stops the voice package learning anything about sync, which
 * arrives from above as a lambda.
 */
interface VoiceLedger {
    val categories: Flow<List<VoiceCategory>>
    val accounts: Flow<List<VoiceAccount>>

    /**
     * The same two lists as Room rows, for EditTransactionDialog.
     *
     * It is not this package's dialog and is not going to be, so it takes
     * entities and gets them from here rather than from a repository handed
     * down into a composable — this interface exists precisely so that one
     * class in this package knows the database is there.
     */
    val editableCategories: Flow<List<CategoryEntity>>
    val editableAccounts: Flow<List<AccountEntity>>

    suspend fun add(
        kind: EntryKind,
        amountMinor: Long,
        accountId: String,
        categoryId: String?,
        occurredAtMs: Long,
        createdBy: String,
    ): String

    suspend fun read(id: String): TransactionEntity?

    suspend fun update(entity: TransactionEntity)

    suspend fun remove(id: String)

    /**
     * Un-revert. A mis-tapped Revert is otherwise unrecoverable from this
     * sheet, and clearing the tombstone is a whole-row write like any other —
     * the row never went anywhere, the same way the server's never do.
     */
    suspend fun restore(id: String)
}

/** [VoiceLedger] over the real thing. */
class RepositoryVoiceLedger(private val repository: MonyxRepository) : VoiceLedger {

    override val categories: Flow<List<VoiceCategory>> = repository.categories().map { rows ->
        rows.map { row ->
            VoiceCategory(
                id = row.id,
                name = row.name,
                // A row whose kind is neither is a transfer category from before
                // transfers were withdrawn. Filing an expense is the safe
                // reading, and the parser matches on the name either way.
                kind = if (row.kind == EntryKind.Income.wire) EntryKind.Income else EntryKind.Expense,
                parentId = row.parentId,
                icon = row.icon,
                color = row.color,
            )
        }
    }

    override val editableCategories: Flow<List<CategoryEntity>> = repository.categories()

    /** accounts(), not activeAccounts(): a closed card is not somewhere to move
     *  money TO, but the one an existing row already sits on has to stay
     *  offered or editing its note would show no account selected at all. */
    override val editableAccounts: Flow<List<AccountEntity>> = repository.accounts()

    /** activeAccounts(), so "the default account" means the same thing here as
     *  it does in AddViewModel.ensureDefaultAccount — an archived card is not
     *  somewhere to put money. */
    override val accounts: Flow<List<VoiceAccount>> = repository.activeAccounts().map { rows ->
        rows.map { VoiceAccount(id = it.id, name = it.name) }
    }

    /**
     * source = "voice". The column and its CHECK constraint have allowed the
     * value since the first migration and Mapping.kt already round-trips it, so
     * a durable marker for every row this feature wrote costs no schema change
     * at all.
     */
    override suspend fun add(
        kind: EntryKind,
        amountMinor: Long,
        accountId: String,
        categoryId: String?,
        occurredAtMs: Long,
        createdBy: String,
    ): String = repository.addTransaction(
        kind = kind.wire,
        amountMinor = amountMinor,
        accountId = accountId,
        categoryId = categoryId,
        occurredAtMs = occurredAtMs,
        createdBy = createdBy,
        source = "voice",
    )

    override suspend fun read(id: String): TransactionEntity? = repository.transaction(id)

    override suspend fun update(entity: TransactionEntity) = repository.updateTransaction(entity)

    override suspend fun remove(id: String) = repository.deleteTransaction(id)

    override suspend fun restore(id: String) {
        val existing = repository.transaction(id) ?: return
        repository.updateTransaction(existing.copy(deleted = 0))
    }
}
