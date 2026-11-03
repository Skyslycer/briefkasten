package de.skyslycer.briefkasten

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.Normalizer
import java.util.Locale

@Entity(tableName = "letter_groups")
data class LetterGroup(@PrimaryKey val id: String, val name: String, val color: Int)

val GROUP_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE letters ADD COLUMN groupId TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS letter_groups (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color INTEGER NOT NULL)")
    }
}

fun normalizedGroupText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

/** Literal case-insensitive contains, like ILIKE '%name%'; longest match wins, stable ties by ID. */
fun matchingGroup(text: String, groups: List<LetterGroup>): LetterGroup? {
    return matchingGroupReadings(listOf(text), groups)
}

/** Match each independent reading separately so a phrase cannot span two OCR passes. */
fun matchingGroupReadings(readings: List<String>, groups: List<LetterGroup>): LetterGroup? {
    val normalized = readings.map(::normalizedGroupText)
    return groups.filter { normalizedGroupText(it.name).let { name -> name.isNotEmpty() && normalized.any { it.contains(name) } } }
        .sortedWith(compareByDescending<LetterGroup> { normalizedGroupText(it.name).length }.thenBy { it.id }).firstOrNull()
}
