package de.skyslycer.briefkasten

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class BackupExportIntegrationTest {
    @Test fun portableBackupContainsCompleteArchiveAndVerifiedAssets(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BriefkastenApp
        ArchiveFixture(app).use { fixture ->
            fixture.seed()
            val dao = fixture.database.archive()
            val letters = dao.all()
            val backup = fixture.exporter.prepare()
            assertEquals(letters.size, backup.count)
            ZipFile(backup.file).use { zip ->
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                assertEquals("de.skyslycer.briefkasten.backup", manifest.getString("format"))
                assertEquals(1, manifest.getInt("version"))
                assertEquals("all_time", manifest.getString("scope"))
                assertFalse(manifest.getBoolean("authenticationIncluded"))
                assertEquals(dao.groups().size, manifest.getJSONArray("groups").length())
                assertEquals(dao.allSources().size, manifest.getJSONArray("sources").length())
                assertEquals(dao.allAccounts().size, manifest.getJSONArray("accounts").length())
                val records = manifest.getJSONArray("letters")
                assertEquals(letters.size, records.length())
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)
                    val letter = letters.single { it.id == record.getString("id") }
                    assertEquals(letter.firstSeen, record.getLong("firstSeen"))
                    assertEquals(letter.date, record.getString("date"))
                    assertArrayEquals(fixture.vault.read(letter.fileName), zip.getInputStream(zip.getEntry(record.getString("original"))).readBytes())
                }
                val assets = manifest.getJSONArray("assets")
                val names = mutableSetOf<String>()
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    assertTrue(names.add(asset.getString("path")))
                    val bytes = zip.getInputStream(zip.getEntry(asset.getString("path"))).readBytes()
                    assertEquals(asset.getInt("bytes"), bytes.size)
                    assertEquals(asset.getString("sha256"), sha256(bytes))
                }
                assertEquals(assets.length() + 2, zip.size())
            }
        }
        Unit
    }

    @Test fun saveIntentsChooseSaveAsOrFolderAndSavingCopiesEveryByte() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BriefkastenApp
        val folder = newExportFolder(app.cacheDir)
        val bytes = ByteArray(256_000) { (it % 251).toByte() }
        val source = File(folder, "source.zip").apply { writeBytes(bytes) }
        val export = PendingExport(listOf(source), "application/zip")
        val intent = exportSaveIntent(export)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/zip", intent.type)
        assertEquals("source.zip", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, exportSaveIntent(PendingExport(listOf(source, source), "application/pdf")).action)
        val target = File(folder, "saved.zip").apply { createNewFile() }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.exports", target)
        saveExport(app.contentResolver, export, uri)
        assertArrayEquals(bytes, target.readBytes())
    }
}
