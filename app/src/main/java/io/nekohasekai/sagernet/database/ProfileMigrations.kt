package io.nekohasekai.sagernet.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Missing historical paths; defaults match ProxyGroup's existing model. */
internal object ProfileMigrations {
    val from1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `proxy_groups` ADD COLUMN `isSelector` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `proxy_groups` ADD COLUMN `frontProxy` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `proxy_groups` ADD COLUMN `landingProxy` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `shadowTLSBean` BLOB")
        }
    }
    val from2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `mieruBean` BLOB")
        }
    }
}
