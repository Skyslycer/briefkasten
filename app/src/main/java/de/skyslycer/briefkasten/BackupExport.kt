package de.skyslycer.briefkasten

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

private data class BackupSnapshot(val letters: List<Letter>, val groups: List<LetterGroup>,
    val sources: List<Source>, val accounts: List<Account>)

/** Portable plaintext logical backup, not unusable copies of device-key-encrypted vault files. */
class BackupExport(private val app: BriefkastenApp,
    private val database: ArchiveDatabase = app.database,
    private val vault: Vault = app.vault,
    private val preferences: Preferences = app.preferences) {
    suspend fun prepare(): SharedArchive = app.operation.withLock { withContext(Dispatchers.IO) {
        val snapshot = database.withTransaction {
            val dao = database.archive()
            BackupSnapshot(dao.all(), dao.groups(), dao.allSources(), dao.allAccounts())
        }
        val settings = preferences.flow.first()
        val folder = newExportFolder(app.cacheDir)
        val range = if (snapshot.letters.isEmpty()) "empty" else exportMonthRange(ExportPeriod.ALL, snapshot.letters)
        val file = File(folder, "Briefkasten_backup_$range.zip")
        try {
            val assets = JSONArray()
            val letters = JSONArray()
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                fun asset(path: String, bytes: ByteArray): String {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes); zip.closeEntry()
                    assets.put(JSONObject().put("path", path).put("bytes", bytes.size).put("sha256", sha256(bytes)))
                    return path
                }
                for (letter in snapshot.letters) {
                    coroutineContext.ensureActive()
                    require(letter.id.matches(Regex("[a-zA-Z0-9_.-]+")) && letter.fileName.matches(Regex("[a-zA-Z0-9_.-]+")))
                    val bytes = vault.read(letter.fileName) ?: error("An archived image is missing. Backup cancelled.")
                    check(sha256(bytes) == letter.contentHash) { "An archived image failed its integrity check. Backup cancelled." }
                    val entry = JSONObject().put("id", letter.id).put("account", letter.account).put("date", letter.date)
                        .put("firstSeen", letter.firstSeen).put("fileName", letter.fileName).put("contentHash", letter.contentHash)
                        .put("occurrence", letter.occurrence).put("notificationPending", letter.notificationPending)
                        .put("groupId", letter.groupId ?: JSONObject.NULL).put("received", letter.received)
                        .put("original", asset("originals/${letter.fileName}", bytes))
                    vault.read("${letter.id}.cutout-v1")?.let {
                        entry.put("cutout", asset("cutouts/${letter.id}.png", it)).put("cutoutVersion", 1)
                    }
                    val ocr = JSONObject()
                    for (view in listOf("full", "cutout")) {
                        vault.read("${letter.id}.ocr-v8-$view")?.let {
                            ocr.put(view, asset("ocr/${letter.id}-$view.json", it))
                        }
                    }
                    entry.put("ocr", ocr).put("ocrVersion", 8)
                    letters.put(entry)
                }
                val groups = JSONArray().apply { snapshot.groups.forEach {
                    put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color))
                } }
                val sources = JSONArray().apply { snapshot.sources.forEach {
                    put(JSONObject().put("source", it.source).put("letterId", it.letterId))
                } }
                val accounts = JSONArray().apply { snapshot.accounts.forEach {
                    put(JSONObject().put("id", it.id).put("initialized", it.initialized))
                } }
                val preferences = JSONObject().put("polling", settings.polling).put("lock", settings.lock)
                    .put("pictures", settings.pictures).put("interval", settings.interval).put("lastCheck", settings.lastCheck)
                    .put("status", settings.status).put("notifications", settings.notifications)
                val manifest = JSONObject().put("format", "de.skyslycer.briefkasten.backup").put("version", 1)
                    .put("appVersion", app.packageManager.getPackageInfo(app.packageName, 0).versionName)
                    .put("createdAt", Instant.now().toString()).put("timeZone", ZoneId.systemDefault().id)
                    .put("scope", "all_time").put("encrypted", false)
                    .put("letters", letters).put("groups", groups).put("sources", sources).put("accounts", accounts)
                    .put("settings", preferences).put("assets", assets)
                    .put("authenticationIncluded", false)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(("Briefkasten portable backup, format version 1.\n" +
                    "manifest.json contains the archive, groups, source aliases, account baselines, settings and SHA-256 asset checksums.\n" +
                    "Original images are complete, decrypted source bytes. Cached cutouts and OCR are optional and can be regenerated.\n" +
                    "DHL OAuth tokens, browser sessions and Android Keystore keys are intentionally excluded. Sign in on the new device.\n" +
                    "This ZIP is unencrypted. Restore using Import backup in Briefkasten.\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            SharedArchive(file, snapshot.letters.size)
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
    } }
}
