package de.skyslycer.briefkasten

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Synthetic data with its own database, vault and preferences; never reads the user's archive. */
internal class ArchiveFixture(val app: BriefkastenApp) : AutoCloseable {
    private val directory = File(app.cacheDir, "archive-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(app) { override fun getFilesDir(): File = directory }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = Room.inMemoryDatabaseBuilder(app, ArchiveDatabase::class.java).build()
    val vault = Vault(context)
    val preferences = Preferences(app, PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") })
    val exporter get() = BackupExport(app, database, vault, preferences)
    val importer get() = BackupImport(app, database, vault, preferences)

    suspend fun seed() {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        val bytes = try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("Musterbank - Musterstrasse 12", 50f, 180f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f })
            }
            ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        } finally { bitmap.recycle() }
        val letter = Letter("fixture", "fixture-account", "2026-10-24", 1792832400000,
            "fixture.png", sha256(bytes), 1, false, "fixture-group")
        vault.write(letter.fileName, bytes)
        vault.write("fixture.ocr-v8-full", EnvelopeReadings("Musterbank").toJson().toByteArray())
        vault.write("fixture.cutout-v1", bytes)
        database.archive().saveGroup(LetterGroup("fixture-group", "Musterbank", 0))
        database.archive().insert(letter)
        database.archive().source(Source("fixture-source", letter.id))
        database.archive().account(Account(letter.account, true))
    }

    override fun close() {
        database.close()
        scope.cancel()
        directory.deleteRecursively()
    }
}
