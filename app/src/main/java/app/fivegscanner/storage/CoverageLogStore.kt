package app.fivegscanner.storage

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable

class CoverageLogStore(private val context: Context) {
    data class LogStats(
        val sampleCount: Int,
        val dayCount: Int,
        val estimatedBytes: Long,
    )

    fun displayDirectory(): String = "local app database"

    fun stats(): LogStats = dbQuery {
        val dao = CoverageDatabaseProvider.database(context).coverageSampleDao()
        LogStats(
            sampleCount = dao.count(),
            dayCount = dao.dayCount(),
            estimatedBytes = dao.encodedSizeBytes(),
        )
    }

    fun exportRecentCsv(limit: Int): File {
        val samples = dbQuery {
            CoverageDatabaseProvider.database(context)
                .coverageSampleDao()
                .recentSamples(limit)
                .asReversed()
        }
        return writeCsv(
            fileName = "coverage-recent-${samples.size}.csv",
            sampleJsonLines = samples.map { it.sampleJson },
        )
    }

    fun exportAllCsv(): File {
        val samples = dbQuery {
            CoverageDatabaseProvider.database(context).coverageSampleDao().allSamples()
        }
        return writeCsv(
            fileName = "coverage-all.csv",
            sampleJsonLines = samples.map { it.sampleJson },
        )
    }

    fun exportUri(file: File): Uri =
        CoverageLogFileProvider.exportUriFor(context, file)

    fun deleteAllLogs(): Int = dbQuery {
        CoverageDatabaseProvider.database(context).coverageSampleDao().deleteAll()
    }

    private fun exportDirectory(): File =
        File(context.cacheDir, "coverage-exports").apply {
            mkdirs()
        }

    private fun <T> dbQuery(query: Callable<T>): T =
        CoverageDatabaseProvider.ioExecutor.submit(query).get()

    private fun writeCsv(fileName: String, sampleJsonLines: List<String>): File {
        val csvFile = File(exportDirectory(), fileName)
        csvFile.writeText(toCsv(sampleJsonLines), Charsets.UTF_8)
        return csvFile
    }

    private fun toCsv(sampleJsonLines: Sequence<String>): String =
        toCsv(sampleJsonLines.toList())

    private fun toCsv(sampleJsonLines: List<String>): String {
        val rows = buildList {
            sampleJsonLines.asSequence()
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
