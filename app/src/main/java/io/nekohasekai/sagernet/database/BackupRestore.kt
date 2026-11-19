package io.nekohasekai.sagernet.database

import android.content.Context
import android.os.Parcel
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.matsuri.nb4a.utils.ImportLimits
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

internal object BackupRestore {
    class InvalidBackup : IllegalArgumentException()

    data class Profiles(val nodes: List<ProxyEntity>, val groups: List<ProxyGroup>)

    data class Contents(
        val profiles: Profiles?,
        val rules: List<RuleEntity>?,
        val settings: List<KeyValuePair>?,
    )

    suspend fun decode(content: JSONObject, profiles: Boolean, rules: Boolean, settings: Boolean): Contents {
        if (content.optInt("version") != 1) throw InvalidBackup()
        return Contents(
            if (profiles && content.has("profiles")) Profiles(
                decodeBlock(content, "profiles") { ProxyEntity.CREATOR.createFromParcel(it).apply { requireBean() } },
                decodeBlock(content, "groups", ProxyGroup.CREATOR::createFromParcel),
            ) else null,
            if (rules && content.has("rules")) decodeBlock(content, "rules", ParcelizeBridge::createRule) else null,
            if (settings && content.has("settings")) decodeBlock(content, "settings") {
                KeyValuePair.CREATOR.createFromParcel(it).also(::validateSetting)
            } else null,
        )
    }

    private suspend fun <T> decodeBlock(content: JSONObject, key: String, read: (Parcel) -> T): List<T> {
        try {
            val entries = content.getJSONArray(key)
            ImportLimits.checkNodeCount(entries.length().toLong())
            return List(entries.length()) { index ->
                currentCoroutineContext().ensureActive()
                val bytes = Util.b64Decode(entries.get(index) as String)
                val parcel = Parcel.obtain()
                try {
                    parcel.unmarshall(bytes, 0, bytes.size)
                    parcel.setDataPosition(0)
                    val value = read(parcel)
                    require(parcel.dataAvail() == 0)
                    value
                } finally {
                    parcel.recycle()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Deserializer errors can contain the payload of a damaged backup.
            throw InvalidBackup()
        }
    }

    @Suppress("DEPRECATION")
    private fun validateSetting(setting: KeyValuePair) {
        val size = setting.value.size
        when (setting.valueType) {
            KeyValuePair.TYPE_UNINITIALIZED -> require(size == 0)
            KeyValuePair.TYPE_BOOLEAN -> require(size == 1)
            KeyValuePair.TYPE_FLOAT, KeyValuePair.TYPE_INT -> require(size == 4)
            KeyValuePair.TYPE_LONG -> require(size == 8)
            KeyValuePair.TYPE_STRING -> Unit
            KeyValuePair.TYPE_STRING_SET -> {
                val buffer = ByteBuffer.wrap(setting.value)
                while (buffer.hasRemaining()) {
                    require(buffer.remaining() >= Int.SIZE_BYTES)
                    val length = buffer.int
                    require(length in 0..buffer.remaining())
                    buffer.position(buffer.position() + length)
                }
            }
            else -> throw InvalidBackup()
        }
    }

    fun apply(context: Context, contents: Contents) {
        val settings = contents.settings
        if (contents.profiles == null && contents.rules == null) {
            if (settings != null) {
                val db = PublicDatabase.instance
                db.runInTransaction {
                    db.keyValuePairDao().reset()
                    db.keyValuePairDao().insert(settings)
                }
            }
        } else if (settings == null) {
            write(SagerDatabase.instance, contents)
        } else {
            val settingsPath = checkNotNull(PublicDatabase.instance.openHelper.writableDatabase.path)
            val main = SagerDatabase.openForRestore(context)
            try {
                write(main, contents, settingsPath)
            } finally {
                main.close()
            }
        }
    }

    // A mixed restore owns a disposable Room connection. ATTACH lets SQLite
    // recover the two rollback journals together if the process dies in COMMIT.
    internal fun write(main: SagerDatabase, contents: Contents, settingsPath: String? = null) {
        require((settingsPath != null) == (contents.settings != null))
        val connection = main.openHelper.writableDatabase
        if (settingsPath != null) {
            val mainPath = checkNotNull(connection.path)
            check(File(mainPath).isFile && File(settingsPath).isFile && mainPath != settingsPath)
            connection.execSQL("ATTACH DATABASE ? AS restore_settings", arrayOf(settingsPath))
            for (schema in listOf("main", "restore_settings")) {
                connection.query("PRAGMA $schema.journal_mode").use {
                    check(it.moveToFirst() && it.getString(0) in setOf("delete", "truncate", "persist")) {
                        "Backup restore requires on-disk SQLite rollback journals"
                    }
                }
                connection.execSQL("PRAGMA $schema.synchronous = FULL")
                connection.query("PRAGMA $schema.synchronous").use {
                    check(it.moveToFirst() && it.getInt(0) >= 2) {
                        "Backup restore requires durable SQLite transactions"
                    }
                }
            }
        }
        main.runInTransaction {
            contents.profiles?.let {
                main.proxyDao().reset()
                main.proxyDao().insert(it.nodes)
                main.groupDao().reset()
                main.groupDao().insert(it.groups)
            }
            contents.rules?.let {
                main.rulesDao().reset()
                main.rulesDao().insert(it)
            }
            contents.settings?.let { settings ->
                // Use this transaction's connection for settings as well.
                connection.execSQL("DELETE FROM restore_settings.KeyValuePair")
                connection.compileStatement(
                    "INSERT INTO restore_settings.KeyValuePair (`key`, valueType, value) VALUES (?, ?, ?)"
                ).use { insert ->
                    for (setting in settings) {
                        insert.bindString(1, setting.key)
                        insert.bindLong(2, setting.valueType.toLong())
                        insert.bindBlob(3, setting.value)
                        insert.executeInsert()
                    }
                }
            }
        }
    }
}
