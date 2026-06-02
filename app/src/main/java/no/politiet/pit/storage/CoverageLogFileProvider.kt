package no.politiet.pit.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class CoverageLogFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains("w")) {
            throw FileNotFoundException("Coverage logs are read-only")
        }
        val file = fileForUri(uri)
        if (!file.isFile) {
            throw FileNotFoundException("Coverage log not found: $uri")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = CSV_MIME_TYPE

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileForUri(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            val row: Array<Any?> = columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }.toTypedArray()
            addRow(row)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun fileForUri(uri: Uri): File {
        val segments = uri.pathSegments
        if (segments.size != 2) {
            throw FileNotFoundException("Invalid coverage export URI: $uri")
        }
        return when (segments[0]) {
            PATH_EXPORTS -> {
                if (!isCoverageCsvName(segments[1])) {
                    throw FileNotFoundException("Invalid coverage export URI: $uri")
                }
                File(exportDirectory(), segments[1])
            }
            else -> throw FileNotFoundException("Invalid coverage export URI: $uri")
        }
    }

    private fun exportDirectory(): File =
        File((context ?: throw FileNotFoundException("Provider context unavailable")).cacheDir, "coverage-exports")

    private fun isCoverageCsvName(name: String): Boolean =
        name.startsWith("coverage-") && name.endsWith(".csv") && !name.contains(File.separatorChar)

    companion object {
        private const val PATH_EXPORTS = "exports"
        private const val CSV_MIME_TYPE = "text/csv"

        fun exportUriFor(context: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.coverage-exports")
                .appendPath(PATH_EXPORTS)
                .appendPath(file.name)
                .build()
    }
}
