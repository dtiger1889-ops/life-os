package com.example.lifeos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * On-device store for the starter kit's one reference dashboard (casework): the `leads`
 * table plus the generic `outbox`. Fresh version 1 -- no migrations needed yet. A second
 * dashboard's tables would be added the same way the reference deployment grew (an
 * additive Migration that only CREATE TABLEs the new entities), sharing this one outbox
 * discriminated by `OutboxEntity.db`.
 *
 * exportSchema = false -- no migration test harness in this starter kit.
 */
@Database(
    entities = [LeadEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LifeOsDatabase : RoomDatabase() {
    abstract fun leadDao(): LeadDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var INSTANCE: LifeOsDatabase? = null

        fun getInstance(context: Context): LifeOsDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeOsDatabase::class.java,
                    "lifeos.db",
                ).build().also { INSTANCE = it }
            }
    }
}
