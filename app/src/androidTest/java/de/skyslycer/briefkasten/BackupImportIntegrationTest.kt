package de.skyslycer.briefkasten

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.*

class BackupImportIntegrationTest {
    @Test fun roundTripAndValidation(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BriefkastenApp
        ArchiveFixture(app).use { fixture ->
            fixture.seed()
            val before = fixture.database.archive().all()
            check(before.isNotEmpty())
            val backup = fixture.exporter.prepare().file
            fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.exports", file)
            ArchiveFixture(app).use { destination ->
                assertEquals(before.size, destination.importer.restore(uri(backup)))
                assertEquals(before, destination.database.archive().all())
                assertEquals(fixture.database.archive().groups(), destination.database.archive().groups())
                assertEquals(fixture.database.archive().allSources(), destination.database.archive().allSources())
                assertEquals(fixture.database.archive().allAccounts(), destination.database.archive().allAccounts())
                before.forEach { letter ->
                    assertArrayEquals(fixture.vault.read(letter.fileName), destination.vault.read(letter.fileName))
                }
                assertArrayEquals(fixture.vault.read("fixture.ocr-v8-full"), destination.vault.read("fixture.ocr-v8-full"))
                assertArrayEquals(fixture.vault.read("fixture.cutout-v1"), destination.vault.read("fixture.cutout-v1"))
            }
            assertEquals(0, fixture.importer.restore(uri(backup)))
            val id = "import-test-${java.util.UUID.randomUUID()}"
            var name = ""
            val fixtureZip = File(backup.parentFile, "restore-test.zip")
            var original = byteArrayOf()
            ZipFile(backup).use { source ->
                val manifest = JSONObject(source.getInputStream(source.getEntry("manifest.json")).bufferedReader().readText())
                val record = JSONObject(manifest.getJSONArray("letters").getJSONObject(0).toString())
                original = source.getInputStream(source.getEntry(record.getString("original"))).readBytes()
                name = "$id.${record.getString("fileName").substringAfterLast('.')}"
                record.put("id", id).put("fileName", name).put("original", "originals/$name").put("ocr", JSONObject())
                record.remove("cutout")
                manifest.getJSONArray("letters").put(record)
                manifest.getJSONArray("assets").put(JSONObject().put("path", "originals/$name").put("bytes", original.size).put("sha256", sha256(original)))
                ZipOutputStream(fixtureZip.outputStream()).use { zip ->
                    source.entries().asSequence().filter { it.name != "manifest.json" }.forEach {
                        zip.putNextEntry(ZipEntry(it.name)); zip.write(source.getInputStream(it).readBytes()); zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("originals/$name")); zip.write(original); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
                }
            }
            try {
                assertEquals(1, fixture.importer.restore(uri(fixtureZip)))
                assertTrue(fixture.database.archive().find(id)!!.received)
                assertFalse(fixture.database.archive().find(id)!!.notificationPending)
                assertArrayEquals(original, fixture.vault.read(name))
                assertEquals(0, fixture.importer.restore(uri(fixtureZip)))
                for (unsafe in listOf(false, true)) {
                    val bad = File(backup.parentFile, "bad-$unsafe.zip")
                    ZipFile(fixtureZip).use { source -> ZipOutputStream(bad.outputStream()).use { zip ->
                        source.entries().asSequence().forEach {
                            zip.putNextEntry(ZipEntry(if (unsafe && it.name == "manifest.json") "../manifest.json" else it.name))
                            zip.write(if (!unsafe && it.name == "originals/$name") byteArrayOf(1, 2, 3) else source.getInputStream(it).readBytes())
                            zip.closeEntry()
                        }
                    } }
                    assertTrue(runCatching { fixture.importer.restore(uri(bad)) }.isFailure)
                    assertEquals(before.size + 1, fixture.database.archive().all().size)
                }
            } finally {
                fixture.database.openHelper.writableDatabase.execSQL("DELETE FROM letters WHERE id = ?", arrayOf(id))
                fixture.vault.remove(name)
            }
            assertEquals(before, fixture.database.archive().all())
        }
        Unit
    }
}
