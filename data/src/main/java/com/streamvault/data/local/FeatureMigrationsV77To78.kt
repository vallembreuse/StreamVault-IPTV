package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object FeatureMigrationsV77To78 {
    val MIGRATION_77_78 = object : Migration(77, 78) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS nas_transfers (
                    id TEXT NOT NULL,
                    download_id TEXT,
                    content_name TEXT NOT NULL,
                    local_file_name TEXT NOT NULL,
                    local_source_uri TEXT NOT NULL,
                    local_size_bytes INTEGER NOT NULL,
                    remote_directory TEXT NOT NULL,
                    remote_final_name TEXT NOT NULL,
                    remote_temporary_name TEXT NOT NULL,
                    status TEXT NOT NULL,
                    bytes_transferred INTEGER NOT NULL,
                    total_bytes INTEGER NOT NULL,
                    last_error TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    PRIMARY KEY(id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_nas_transfers_status ON nas_transfers(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_nas_transfers_download_id ON nas_transfers(download_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_nas_transfers_updated_at ON nas_transfers(updated_at)")
        }
    }
}
