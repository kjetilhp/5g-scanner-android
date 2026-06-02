package no.politiet.pit.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CoverageLogStore(private val context: Context) {
    private val dayFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

    data class LogFile(
        val name: String,
        val sizeBytes: Long,
        val modifiedAtMillis: Long,
        val uri: Uri?,
        val file: File?,
    )

    data class LogStats(
        val fileCount: Int,
        val totalBytes: Long,
    )

    fun append(sampleJson: String, capturedAt: Instant): String {
        val fileName = "coverage-${dayFormatter.format(capturedAt)}.jsonl"
        val line = if (sampleJson.endsWith("\n")) sampleJson else "$sampleJson\n"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendWithMediaStore(fileName, line)
            return "Documents/Ask/$fileName"
        }

        val file = appendWithExternalFile(fileName, line)
        return file.absolutePath
    }

    fun displayDirectory(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Documents/Ask"
        } else {
            publicLogDirectory().absolutePath
        }

    fun stats(): LogStats {
        val logs = listLogs()
        return LogStats(
            fileCount = logs.size,
            totalBytes = logs.sumOf { it.sizeBytes },
        )
    }

    fun listLogs(): List<LogFile> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listMediaStoreLogs()
        } else {
            listExternalFileLogs()
        }.sortedWith(
            compareByDescending<LogFile> { dateKey(it.name) }
                .thenByDescending { it.modifiedAtMillis }
                .thenBy { it.name },
        )

    fun read(logFile: LogFile): String {
        val bytes = when {
            logFile.uri != null -> context.contentResolver.openInputStream(logFile.uri)?.use { input ->
                input.readBytes()
            }
            logFile.file != null && logFile.file.exists() -> logFile.file.readBytes()
            else -> null
        }
        return bytes?.toString(Charsets.UTF_8).orEmpty()
    }

    fun exportCsv(logFile: LogFile): File {
        val exportDirectory = File(context.cacheDir, "coverage-exports").apply {
            mkdirs()
        }
        val csvFile = File(exportDirectory, logFile.name.removeSuffix(".jsonl") + ".csv")
        csvFile.writeText(toCsv(read(logFile)), Charsets.UTF_8)
        return csvFile
    }

    fun exportUri(csvFile: File): Uri =
        CoverageLogFileProvider.exportUriFor(context, csvFile)

    fun deleteAllLogs(): Int =
        listLogs().count { logFile ->
            when {
                logFile.uri != null -> context.contentResolver.delete(logFile.uri, null, null) > 0
                logFile.file != null -> logFile.file.delete()
                else -> false
            }
        }

    private fun appendWithMediaStore(fileName: String, line: String) {
        val resolver = context.contentResolver
        val uri = findMediaStoreFile(resolver, fileName) ?: createMediaStoreFile(resolver, fileName)
        resolver.openOutputStream(uri, "wa")?.use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
        } ?: error("Could not open coverage log for writing: $fileName")
    }

    private fun findMediaStoreFile(resolver: ContentResolver, fileName: String): Uri? {
        val collection = mediaStoreCollection()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val args = arrayOf(fileName)

        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.isAskCoverageLog()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
        }
        return null
    }

    private fun createMediaStoreFile(resolver: ContentResolver, fileName: String): Uri {
        val collection = mediaStoreCollection()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Ask/")
        }
        return resolver.insert(collection, values)
            ?: error("Could not create coverage log: $fileName")
    }

    private fun appendWithExternalFile(fileName: String, line: String): File {
        val publicDir = publicLogDirectory()
        val dir = if (publicDir.mkdirs() || publicDir.isDirectory) {
            publicDir
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Ask").apply {
                mkdirs()
            }
        }
        val file = File(dir, fileName)
        FileWriter(file, true).use { writer ->
            writer.write(line)
        }
        return file
    }

    private fun listMediaStoreLogs(): List<LogFile> {
        val resolver = context.contentResolver
        return queryMediaStoreLogs(resolver)
    }

    private fun queryMediaStoreLogs(resolver: ContentResolver): List<LogFile> {
        val collection = mediaStoreCollection()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("coverage-%.jsonl%")

        return resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    if (cursor.isAskCoverageLog() && isCoverageLogName(name)) {
                        add(cursor.toLogFile(collection))
                    }
                }
            }
        }.orEmpty()
    }

    private fun Cursor.isAskCoverageLog(): Boolean {
        val relativePath = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
        return relativePath == "${Environment.DIRECTORY_DOCUMENTS}/Ask/"
    }

    private fun Cursor.toLogFile(collection: Uri): LogFile {
        val id = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        val name = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
        val size = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
        val modifiedSeconds = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED))
        return LogFile(
            name = name,
            sizeBytes = size,
            modifiedAtMillis = modifiedSeconds * 1000L,
            uri = Uri.withAppendedPath(collection, id.toString()),
            file = null,
        )
    }

    private fun listExternalFileLogs(): List<LogFile> =
        publicLogDirectory()
            .listFiles { file -> file.isFile && isCoverageLogName(file.name) }
            .orEmpty()
            .map { file ->
                LogFile(
                    name = file.name,
                    sizeBytes = file.length(),
                    modifiedAtMillis = file.lastModified(),
                    uri = null,
                    file = file,
                )
            }

    private fun isCoverageLogName(name: String): Boolean =
        name.startsWith("coverage-") && name.endsWith(".jsonl")

    private fun dateKey(name: String): String =
        name.removePrefix("coverage-").removeSuffix(".jsonl")

    private fun toCsv(jsonl: String): String {
        val rows = buildList {
            jsonl.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    runCatching {
                        addAll(JSONObject(line).toCsvRows())
                    }
                }
        }
        return buildString {
            appendLine(CSV_COLUMNS.joinToString(","))
            rows.forEach { row ->
                appendLine(CSV_COLUMNS.joinToString(",") { column -> csvEscape(row[column].orEmpty()) })
            }
        }
    }

    private fun JSONObject.toCsvRows(): List<Map<String, String>> {
        val kind = optStringValue("kind")
        val fix = optJSONObject("fix")
        val base = mapOf(
            "sample_kind" to kind,
            "fix_timestamp" to fix.optStringValue("timestamp"),
            "gps_time" to fix.optStringValue("gpsTime"),
            "lat" to fix.optStringValue("lat"),
            "lon" to fix.optStringValue("lon"),
            "altitude" to fix.optStringValue("altitude"),
            "speed" to fix.optStringValue("speed"),
            "heading" to fix.optStringValue("heading"),
            "hdop" to fix.optStringValue("hdop"),
            "satellites" to fix.optStringValue("satellites"),
        )
        return when {
            has("cell") -> listOf(base + cellValues(optJSONObject("cell"), "serving", 0))
            has("cells") -> optJSONArray("cells").toCellRows(base, kind)
            else -> listOf(base)
        }
    }

    private fun JSONArray?.toCellRows(base: Map<String, String>, kind: String): List<Map<String, String>> {
        if (this == null || length() == 0) return listOf(base)
        return List(length()) { index ->
            base + cellValues(optJSONObject(index), kind.ifBlank { "cell" }, index)
        }
    }

    private fun cellValues(cell: JSONObject?, role: String, index: Int): Map<String, String> {
        val signal = cell?.optJSONObject("signal")
        return mapOf(
            "cell_role" to role,
            "cell_index" to index.toString(),
            "rat" to cell.optStringValue("rat"),
            "mcc" to cell.optStringValue("mcc"),
            "mnc" to cell.optStringValue("mnc"),
            "cell_id" to cell.optStringValue("cellId"),
            "tac" to cell.optStringValue("tac"),
            "pci" to cell.optStringValue("pci"),
            "arfcn" to cell.optStringValue("arfcn"),
            "earfcn" to cell.optStringValue("earfcn"),
            "nrarfcn" to cell.optStringValue("nrarfcn"),
            "band" to cell.optStringValue("band"),
            "rsrp" to signal.optStringValue("rsrp"),
            "rsrq" to signal.optStringValue("rsrq"),
            "rssi" to signal.optStringValue("rssi"),
            "sinr" to signal.optStringValue("sinr"),
        )
    }

    private fun JSONObject?.optStringValue(name: String): String {
        if (this == null || isNull(name) || !has(name)) return ""
        return opt(name)?.toString().orEmpty()
    }

    private fun csvEscape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun publicLogDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Ask")

    private fun mediaStoreCollection(): Uri =
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    companion object {
        private val CSV_COLUMNS = listOf(
            "sample_kind",
            "fix_timestamp",
            "gps_time",
            "lat",
            "lon",
            "altitude",
            "speed",
            "heading",
            "hdop",
            "satellites",
            "cell_role",
            "cell_index",
            "rat",
            "mcc",
            "mnc",
            "cell_id",
            "tac",
            "pci",
            "arfcn",
            "earfcn",
            "nrarfcn",
            "band",
            "rsrp",
            "rsrq",
            "rssi",
            "sinr",
        )
    }
}
