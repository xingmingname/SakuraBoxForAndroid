package io.nekohasekai.sagernet.database

import android.os.Parcel
import android.os.Parcelable
import android.test.InstrumentationTestCase
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

@Suppress("DEPRECATION")
class BackupRestoreTest : InstrumentationTestCase() {
    fun testMixedRestorePreservesIdsOrderAndLegacyPreference() = runBlocking {
        withDatabases { main, settings ->
            val backup = BackupRestore.decode(backup(), profiles = true, rules = true, settings = true)
            BackupRestore.write(main, backup, settingsPath(settings))
            assertEquals(2L, main.proxyDao().getAll().single().id)
            assertEquals(11L, main.proxyDao().getAll().single().groupId)
            assertEquals(24L, main.proxyDao().getAll().single().userOrder)
            assertEquals(11L, main.groupDao().allGroups().single().id)
            assertEquals(12L, main.rulesDao().allRules().single().id)
            assertEquals(73L, settings.keyValuePairDao().all().single().long)
        }
    }

    fun testBrokenProfilesAndGroupsNeverReachDatabaseWrites() = runBlocking {
        withDatabases { main, settings ->
            for (section in listOf("profiles", "groups", "rules", "settings")) {
                val bad = backup().put(section, JSONArray().put("A"))
                expect<BackupRestore.InvalidBackup> {
                    val data = BackupRestore.decode(bad, profiles = true, rules = true, settings = true)
                    BackupRestore.write(main, data, settingsPath(settings))
                }
                assertOriginal(main, settings)
            }
        }
    }

    fun testRuleFailureRollsBackNodesAndGroups() = runBlocking {
        withDatabases { main, settings ->
            main.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_rules BEFORE INSERT ON rules BEGIN SELECT RAISE(ABORT, 'test'); END"
            )
            val backup = BackupRestore.decode(backup(), profiles = true, rules = true, settings = true)
            expect<android.database.sqlite.SQLiteConstraintException> {
                BackupRestore.write(main, backup, settingsPath(settings))
            }
            assertOriginal(main, settings)
        }
    }

    fun testSettingsFailureRollsBackBothDatabases() = runBlocking {
        withDatabases { main, settings ->
            settings.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_settings BEFORE INSERT ON KeyValuePair BEGIN SELECT RAISE(ABORT, 'test'); END"
            )
            val backup = BackupRestore.decode(backup(), profiles = true, rules = true, settings = true)
            expect<android.database.sqlite.SQLiteConstraintException> {
                BackupRestore.write(main, backup, settingsPath(settings))
            }
            assertOriginal(main, settings)
        }
    }

    fun testCancellationAndUnselectedBlocksLeaveOriginalData() = runBlocking {
        withDatabases { main, settings ->
            val cancelled = Job().apply { cancel() }
            expect<CancellationException> {
                withContext(cancelled) {
                    val data = BackupRestore.decode(backup(), profiles = true, rules = true, settings = true)
                    BackupRestore.write(main, data, settingsPath(settings))
                }
            }
            val unselected = BackupRestore.decode(
                backup().put("groups", "malformed"),
                profiles = false, rules = false, settings = false,
            )
            BackupRestore.write(main, unselected)
            assertOriginal(main, settings)
        }
    }

    private suspend fun withDatabases(block: suspend (SagerDatabase, PublicDatabase) -> Unit) {
        val context = instrumentation.targetContext
        val id = UUID.randomUUID()
        val mainName = "restore-test-$id-main.db"
        val settingsName = "restore-test-$id-settings.db"
        val main = Room.databaseBuilder(context, SagerDatabase::class.java, mainName)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        val settings = Room.databaseBuilder(context, PublicDatabase::class.java, settingsName)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        try {
            main.proxyDao().insert(listOf(node(1, 10)))
            main.groupDao().insert(listOf(ProxyGroup(id = 10, name = "old group")))
            main.rulesDao().insert(listOf(RuleEntity(id = 11, name = "old rule")))
            settings.keyValuePairDao().insert(listOf(KeyValuePair("old-setting").put("original")))
            block(main, settings)
        } finally {
            main.close()
            settings.close()
            context.deleteDatabase(mainName)
            context.deleteDatabase(settingsName)
        }
    }

    private fun assertOriginal(main: SagerDatabase, settings: PublicDatabase) {
        assertEquals(1L, main.proxyDao().getAll().single().id)
        assertEquals(10L, main.groupDao().allGroups().single().id)
        assertEquals(11L, main.rulesDao().allRules().single().id)
        assertEquals("original", settings.keyValuePairDao().all().single().string)
    }

    private fun settingsPath(settings: PublicDatabase) = checkNotNull(settings.openHelper.writableDatabase.path)

    private fun node(id: Long, group: Long) = ProxyEntity(id = id, groupId = group, userOrder = 24).apply {
        putBean(SOCKSBean().applyDefaultValues())
    }

    private fun backup() = JSONObject().apply {
        put("version", 1)
        put("profiles", JSONArray().put(encode(node(2, 11))))
        put("groups", JSONArray().put(encode(ProxyGroup(id = 11, name = "new group"))))
        put("rules", JSONArray().put(encode(RuleEntity(id = 12, name = "new rule"))))
        // Version 1 archives could contain the former four-byte integer type.
        val legacy = KeyValuePair("legacy-setting").apply {
            valueType = KeyValuePair.TYPE_INT
            value = ByteBuffer.allocate(4).putInt(73).array()
        }
        put("settings", JSONArray().put(encode(legacy)))
    }

    private fun encode(value: Parcelable): String {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        fail("Expected " + T::class.java.simpleName)
    }
}
