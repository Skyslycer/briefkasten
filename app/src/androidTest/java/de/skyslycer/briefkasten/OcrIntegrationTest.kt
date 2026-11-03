package de.skyslycer.briefkasten

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrIntegrationTest {
    @Test fun croppedTextMatchesMostSpecificGroup() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 42f }
        canvas.drawText("DEUTSCHE POST", 180f, 350f, paint)
        canvas.drawText("Musterstrasse 12", 180f, 410f, paint)
        canvas.drawText("12345 Berlin", 180f, 470f, paint)
        val crop = envelopeCutout(bitmap)
        assertTrue(crop.width < bitmap.width)
        try {
            val result = recognizeEnvelopeText(crop)
            assertEquals("specific", matchingGroup(result, listOf(LetterGroup("generic", "Post", 0),
                LetterGroup("specific", "Deutsche Post", 2)))?.id)
        } finally {
            if (crop !== bitmap) crop.recycle()
            bitmap.recycle()
        }
    }

    @Test fun migrationPreservesOldArchive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test-${System.nanoTime()}.db"
        val raw = context.openOrCreateDatabase(name, 0, null)
        raw.execSQL("CREATE TABLE letters (id TEXT NOT NULL PRIMARY KEY, account TEXT NOT NULL, date TEXT NOT NULL, firstSeen INTEGER NOT NULL, fileName TEXT NOT NULL, contentHash TEXT NOT NULL, occurrence INTEGER NOT NULL, notificationPending INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE sources (source TEXT NOT NULL PRIMARY KEY, letterId TEXT NOT NULL)")
        raw.execSQL("CREATE TABLE accounts (id TEXT NOT NULL PRIMARY KEY, initialized INTEGER NOT NULL)")
        raw.execSQL("INSERT INTO letters VALUES ('kept', 'account', '2026-11-02', 1, 'image.jpg', 'hash', 1, 0)")
        raw.version = 1
        raw.close()
        val database = Room.databaseBuilder(context, ArchiveDatabase::class.java, name).addMigrations(GROUP_MIGRATION).build()
        try {
            val letter = database.archive().find("kept")!!
            assertEquals("image.jpg", letter.fileName)
            assertNull(letter.groupId)
            database.archive().saveGroup(LetterGroup("test", "Post", 0))
            database.archive().assignGroup(letter.id, "test")
            assertEquals("test", database.archive().find(letter.id)?.groupId)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
