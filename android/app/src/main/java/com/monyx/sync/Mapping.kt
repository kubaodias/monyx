package com.monyx.sync

import com.monyx.data.AccountEntity
import com.monyx.data.BudgetEntity
import com.monyx.data.MonthPlanEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.MemberEntity
import com.monyx.data.RecurringRuleEntity
import com.monyx.data.TransactionEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Kotlin data class and a TypeScript interface describe the same tables,
 * and the duplication is on purpose. A code generator or an OpenAPI schema
 * in between is a pipeline to maintain in order to save rewriting ~80 lines that
 * change a few times a year. These mappings are that duplication, written out.
 */

private fun JsonObject.str(key: String): String? =
    this[key]?.let { if (it is JsonPrimitive && !it.isString && it.content == "null") null else it.jsonPrimitive.contentOrNullSafe() }

private fun JsonPrimitive.contentOrNullSafe(): String? = if (content == "null") null else content

private fun JsonObject.reqStr(key: String): String = str(key) ?: ""
private fun JsonObject.long(key: String, fallback: Long = 0): Long =
    this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: fallback
private fun JsonObject.int(key: String, fallback: Int = 0): Int =
    this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: fallback

private fun putNullable(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, value: String?) {
    if (value == null) builder.put(key, kotlinx.serialization.json.JsonNull)
    else builder.put(key, JsonPrimitive(value))
}

fun TransactionEntity.toRow(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("kind", JsonPrimitive(kind))
    put("amount_minor", JsonPrimitive(amountMinor))
    put("account_id", JsonPrimitive(accountId))
    putNullable(this, "transfer_account_id", transferAccountId)
    putNullable(this, "category_id", categoryId)
    putNullable(this, "note", note)
    put("occurred_at", JsonPrimitive(occurredAt))
    put("occurred_on", JsonPrimitive(occurredOn))
    put("created_by", JsonPrimitive(createdBy))
    put("source", JsonPrimitive(source))
    putNullable(this, "recurring_rule_id", recurringRuleId)
    put("created_at", JsonPrimitive(createdAt))
    put("deleted", JsonPrimitive(deleted))
}

fun JsonObject.toTransaction(): TransactionEntity = TransactionEntity(
    id = reqStr("id"),
    kind = reqStr("kind"),
    amountMinor = long("amount_minor"),
    accountId = reqStr("account_id"),
    transferAccountId = str("transfer_account_id"),
    categoryId = str("category_id"),
    note = str("note"),
    occurredAt = long("occurred_at"),
    occurredOn = reqStr("occurred_on"),
    createdBy = reqStr("created_by"),
    source = str("source") ?: "manual",
    recurringRuleId = str("recurring_rule_id"),
    createdAt = long("created_at"),
    seq = long("seq"),
    deleted = int("deleted"),
    pending = 0,
    rejected = 0,
)

fun AccountEntity.toRow(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("name", JsonPrimitive(name))
    putNullable(this, "icon", icon)
    putNullable(this, "color", color)
    put("initial_balance_minor", JsonPrimitive(initialBalanceMinor))
    put("sort_order", JsonPrimitive(sortOrder))
    put("archived", JsonPrimitive(archived))
    put("deleted", JsonPrimitive(deleted))
}

fun JsonObject.toAccount(): AccountEntity = AccountEntity(
    id = reqStr("id"),
    name = reqStr("name"),
    icon = str("icon"),
    color = str("color"),
    initialBalanceMinor = long("initial_balance_minor"),
    sortOrder = int("sort_order"),
    // A server that predates archiving simply omits it, and unarchived is the
    // right reading of silence.
    archived = int("archived"),
    seq = long("seq"),
    deleted = int("deleted"),
)

fun CategoryEntity.toRow(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    putNullable(this, "parent_id", parentId)
    put("name", JsonPrimitive(name))
    putNullable(this, "icon", icon)
    putNullable(this, "color", color)
    put("kind", JsonPrimitive(kind))
    put("sort_order", JsonPrimitive(sortOrder))
    put("deleted", JsonPrimitive(deleted))
}

fun JsonObject.toCategory(): CategoryEntity = CategoryEntity(
    id = reqStr("id"),
    parentId = str("parent_id"),
    name = reqStr("name"),
    icon = str("icon"),
    color = str("color"),
    kind = str("kind") ?: "expense",
    sortOrder = int("sort_order"),
    seq = long("seq"),
    deleted = int("deleted"),
)

fun MonthPlanEntity.toRow(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("period", JsonPrimitive(period))
    put("planned_minor", JsonPrimitive(plannedMinor))
    put("deleted", JsonPrimitive(deleted))
}

fun JsonObject.toMonthPlan(): MonthPlanEntity = MonthPlanEntity(
    id = reqStr("id"),
    period = reqStr("period"),
    plannedMinor = long("planned_minor"),
    seq = long("seq"),
    deleted = int("deleted"),
)

fun BudgetEntity.toRow(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("category_id", JsonPrimitive(categoryId))
    put("period", JsonPrimitive(period))
    put("limit_minor", JsonPrimitive(limitMinor))
    put("deleted", JsonPrimitive(deleted))
}

fun JsonObject.toBudget(): BudgetEntity = BudgetEntity(
    id = reqStr("id"),
    categoryId = reqStr("category_id"),
    period = reqStr("period"),
    limitMinor = long("limit_minor"),
    seq = long("seq"),
    deleted = int("deleted"),
)

fun RecurringRuleEntity.toRow(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("kind", JsonPrimitive(kind))
    put("amount_minor", JsonPrimitive(amountMinor))
    put("account_id", JsonPrimitive(accountId))
    putNullable(this, "category_id", categoryId)
    putNullable(this, "note", note)
    put("freq", JsonPrimitive(freq))
    put("starts_on", JsonPrimitive(startsOn))
    putNullable(this, "ends_on", endsOn)
    put("created_by", JsonPrimitive(createdBy))
    put("created_at", JsonPrimitive(createdAt))
    put("deleted", JsonPrimitive(deleted))
}

fun JsonObject.toRecurringRule(): RecurringRuleEntity = RecurringRuleEntity(
    id = reqStr("id"),
    kind = reqStr("kind"),
    amountMinor = long("amount_minor"),
    accountId = reqStr("account_id"),
    categoryId = str("category_id"),
    note = str("note"),
    freq = reqStr("freq"),
    startsOn = reqStr("starts_on"),
    endsOn = str("ends_on"),
    createdBy = reqStr("created_by"),
    createdAt = long("created_at"),
    seq = long("seq"),
    deleted = int("deleted"),
)

fun MemberEntity.toRow(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("name", JsonPrimitive(name))
    put("created_at", JsonPrimitive(createdAt))
    put("deleted", JsonPrimitive(deleted))
}

fun JsonObject.toMember(): MemberEntity = MemberEntity(
    id = reqStr("id"),
    name = reqStr("name"),
    createdAt = long("created_at"),
    seq = long("seq"),
    deleted = int("deleted"),
)
