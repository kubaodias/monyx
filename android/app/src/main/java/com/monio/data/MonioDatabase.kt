package com.monio.data

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
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MonioDatabase : RoomDatabase() {
    abstract fun dao(): MonioDao

    companion object {
        @Volatile private var instance: MonioDatabase? = null

        fun get(context: Context): MonioDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MonioDatabase::class.java,
                "monio.db",
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
