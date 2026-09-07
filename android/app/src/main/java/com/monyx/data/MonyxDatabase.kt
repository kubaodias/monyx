package com.monyx.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MemberEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        BudgetEntity::class,
        MonthPlanEntity::class,
        RecurringRuleEntity::class,
        SyncStateEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class MonyxDatabase : RoomDatabase() {
    abstract fun dao(): MonyxDao

    companion object {
        @Volatile private var instance: MonyxDatabase? = null

        fun get(context: Context): MonyxDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MonyxDatabase::class.java,
                "monyx.db",
            )
                /**
                 * Destructive migration is a legitimate strategy here, not a
                 * data-loss bug, because the sync cursor lives inside this same
                 * database. A wipe resets the cursor to zero, the next sync
                 * re-pulls everything, and the only thing actually lost is what
                 * had not reached the server yet — normally nothing.
                 *
                 * No hand-written Migration classes, ever.
                 */
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
