package com.cipherpath.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.cipherpath.security.DatabaseKey
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        JobEntity::class, ResumeEntity::class, MatchEntity::class, CompanyEntity::class,
        ApplicationEntity::class, InterviewEntity::class, ApplicationEventEntity::class,
        NotificationEntity::class, SyncRunEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jobs(): JobDao
    abstract fun resumes(): ResumeDao
    abstract fun matches(): MatchDao
    abstract fun companies(): CompanyDao
    abstract fun applications(): ApplicationDao
    abstract fun notifications(): NotificationDao
    abstract fun syncRuns(): SyncRunDao

    companion object {
        const val NAME = "cipherpath.db"

        /**
         * Explicit migrations go here as the schema evolves (exported JSON schemas live in
         * app/schemas/ and are checked into the repo). Destructive fallback is deliberately
         * NOT enabled: user data (applications, resumes) must never be silently dropped.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

        fun build(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DatabaseKey.get(context))
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
