package de.skyslycer.briefkasten

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Validate in temporary storage before adding anything to the encrypted archive. */
class BackupImport(private val app: BriefkastenApp,
    private val database: ArchiveDatabase = app.database,
    private val vault: Vault = app.vault,
    private val preferences: Preferences = app.preferences) {
    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val staging = File(app.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val entries = mutableMapOf<String, File>()
            var total = 0L
            app.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open backup." }
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = zip.nextEntry ?: break
                        val path = entry.name
                        require(!entry.isDirectory && path.length <= 240 && path.split('/').all {
                            it.matches(Regex("[a-zA-Z0-9_.-]+")) && it != "." && it != ".."
                        } && entries.size < 100000 && path !in entries) { "Invalid or duplicate backup path." }
                        val file = File(staging, entries.size.toString())
                        var size = 0L
                        file.outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                size += n; total += n
                                require(size <= 64L * 1024 * 1024 && total <= 2L * 1024 * 1024 * 1024) { "Backup exceeds import size limits." }
                                out.write(buffer, 0, n)
                                coroutineContext.ensureActive()
                            }
                        }
                        entries[path] = file
                    }
                }
            }
            val manifestFile = entries["manifest.json"] ?: error("This is not a Briefkasten backup. Choose a backup ZIP.")
            require(manifestFile.length() <= 16 * 1024 * 1024) { "Backup manifest is too large." }
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.getString("format") == "de.skyslycer.briefkasten.backup" && manifest.getInt("version") == 1) { "Unsupported backup format." }
            val assets = mutableSetOf<String>()
            val assetArray = manifest.getJSONArray("assets")
            for (i in 0 until assetArray.length()) {
                val asset = assetArray.getJSONObject(i)
                val path = asset.getString("path")
                val file = entries[path] ?: error("Backup asset is missing.")
                require(assets.add(path) && file.length() == asset.getLong("bytes") && sha256(file.readBytes()) == asset.getString("sha256")) { "Backup integrity check failed." }
            }
            require(entries.keys == assets + setOf("manifest.json", "README.txt")) { "Unexpected backup contents." }
            fun array(name: String) = manifest.getJSONArray(name).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            fun identifier(value: String): String {
                require(value.length in 1..200 && value.matches(Regex("[a-zA-Z0-9_.-]+")) && value !in listOf(".", "..")) { "Invalid backup identifier." }
                return value
            }
            val groups = array("groups").map { LetterGroup(identifier(it.getString("id")), it.getString("name"), it.getInt("color")) }
            val accounts = array("accounts").map { Account(it.getString("id"), it.getBoolean("initialized")) }
            val rows = array("letters")
            val letters = rows.map {
                Letter(identifier(it.getString("id")), it.getString("account"), it.getString("date"), it.getLong("firstSeen"),
                    identifier(it.getString("fileName")), it.getString("contentHash"), it.getInt("occurrence"), false,
                    if (it.isNull("groupId")) null else it.getString("groupId"))
            }
            val sources = array("sources").map { Source(it.getString("source"), it.getString("letterId")) }
            require(groups.map { it.id }.distinct().size == groups.size && letters.map { it.id }.distinct().size == letters.size &&
                letters.map { it.fileName }.distinct().size == letters.size && accounts.map { it.id }.distinct().size == accounts.size &&
                sources.map { it.source }.distinct().size == sources.size) { "Duplicate backup records." }
            val groupIds = groups.map { it.id }.toSet()
            val letterIds = letters.map { it.id }.toSet()
            require(letters.all { it.groupId == null || it.groupId in groupIds } && sources.all { it.letterId in letterIds }) { "Broken backup references." }
            val payloads = mutableMapOf<String, File>()
            letters.forEachIndexed { index, letter ->
                require(letter.fileName.substringAfterLast('.') in listOf("jpg", "png", "webp", "gif")) { "Invalid original filename." }
                val row = rows[index]
                val path = row.getString("original")
                require(path == "originals/${letter.fileName}" && path in assets)
                val file = entries.getValue(path)
                require(sha256(file.readBytes()) == letter.contentHash) { "Original image checksum failed." }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100000000) { "Invalid or oversized image." }
                payloads[letter.fileName] = file
                if (row.has("cutout") && row.optInt("cutoutVersion") == 1) {
                    val cutout = row.getString("cutout")
                    require(cutout == "cutouts/${letter.id}.png" && cutout in assets)
                    BitmapFactory.decodeFile(entries.getValue(cutout).path, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100000000)
                    payloads["${letter.id}.cutout-v1"] = entries.getValue(cutout)
                }
                val ocr = row.optJSONObject("ocr")
                if (row.optInt("ocrVersion") == 8 && ocr != null) for (view in listOf("full", "cutout")) {
                    if (ocr.has(view)) {
                        val pathOcr = ocr.getString(view)
                        require(pathOcr == "ocr/${letter.id}-$view.json" && pathOcr in assets)
                        JSONObject(entries.getValue(pathOcr).readText())
                        payloads["${letter.id}.ocr-v8-$view"] = entries.getValue(pathOcr)
                    }
                }
            }
            val settings = manifest.getJSONObject("settings")
            val pictures = settings.getBoolean("pictures")
            val notifications = settings.getBoolean("notifications")
            val polling = settings.getBoolean("polling")
            val interval = settings.getLong("interval")
            require(interval in listOf(15L, 30L, 60L, 180L))
            app.operation.withLock {
                val dao = database.archive()
                val existing = dao.all().associateBy { it.id }
                val existingFiles = existing.values.associateBy { it.fileName }
                val existingGroups = dao.groups().associateBy { it.id }
                // Preserve current edits to matching groups; conflicting envelope identities must never overwrite data.
                letters.forEach { letter ->
                    existing[letter.id]?.let { require(it.contentHash == letter.contentHash && it.fileName == letter.fileName) { "An existing envelope conflicts with this backup." } }
                    existingFiles[letter.fileName]?.let { require(it.id == letter.id) { "An existing image conflicts with this backup." } }
                }
                sources.forEach { source -> dao.source(source.source)?.let { require(it.letterId == source.letterId) { "An existing source conflicts with this backup." } } }
                val written = mutableListOf<String>()
                try {
                    payloads.forEach { (name, file) ->
                        if (vault.read(name) == null) { written.add(name); vault.write(name, file.readBytes()) }
                    }
                    database.withTransaction {
                        groups.filter { it.id !in existingGroups }.forEach { dao.saveGroup(it) }
                        letters.forEach { dao.insert(it) }
                        sources.forEach { dao.source(it) }
                        accounts.forEach { if (dao.initialized(it.id) == null) dao.account(it) }
                    }
                } catch (e: Exception) {
                    written.forEach { vault.remove(it) }
                    throw e
                }
                // Device lock and OS permission setup remain specific to the destination device.
                preferences.pictures(pictures)
                preferences.interval(interval)
                preferences.notifications(notifications)
                preferences.polling(polling)
                letters.count { it.id !in existing }
            }
        } finally { staging.deleteRecursively() }
    }
}
