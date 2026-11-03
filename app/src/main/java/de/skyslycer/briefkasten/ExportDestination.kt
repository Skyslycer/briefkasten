package de.skyslycer.briefkasten

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

enum class ExportDestination { SHARE, SAVE }
data class PendingExport(val files: List<File>, val mime: String)

fun exportSaveIntent(export: PendingExport): Intent = if (export.files.size == 1) {
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = export.mime
        putExtra(Intent.EXTRA_TITLE, export.files.single().name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
} else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}

/** Only user-created destinations are written; failures remove our own partial files. */
fun saveExport(resolver: ContentResolver, export: PendingExport, destination: Uri) {
    val created = mutableListOf<Uri>()
    try {
        val parent = if (export.files.size > 1) DocumentsContract.buildDocumentUriUsingTree(destination,
            DocumentsContract.getTreeDocumentId(destination)) else null
        for (file in export.files) {
            val uri = if (parent == null) destination else DocumentsContract.createDocument(resolver, parent, export.mime, file.name)
                ?: throw IOException("Could not create ${file.name}.")
            created.add(uri)
            if (!file.isFile) throw IOException("Prepared export expired. Please export again.")
            val written = resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Could not open the selected destination.")
            if (written != file.length()) throw IOException("The export could not be saved completely.")
        }
    } catch (e: Exception) {
        created.forEach { uri -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
        throw e
    }
}
