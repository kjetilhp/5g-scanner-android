package no.politiet.pit

import android.content.Context
import java.util.Locale

class MobileOperatorLookup private constructor(
    private val operatorsByCode: Map<OperatorCode, String>,
) {
    fun nameFor(mcc: Int, mnc: Int): String? = operatorsByCode[OperatorCode(mcc, mnc)]

    fun displayNameFor(mcc: Int, mnc: Int): String =
        nameFor(mcc, mnc) ?: formatCode(mcc, mnc)

    companion object {
        fun fromRawResource(context: Context, resourceId: Int): MobileOperatorLookup {
            val operators = mutableMapOf<OperatorCode, String>()
            context.resources.openRawResource(resourceId).bufferedReader().useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("mcc,") }
                    .forEach { line ->
                        parseCsvRow(line)?.let { row ->
                            operators[OperatorCode(row.mcc, row.mnc)] = row.displayName
                        }
                    }
            }
            return MobileOperatorLookup(operators)
        }

        private fun parseCsvRow(line: String): OperatorRow? {
            val parts = line.split(",", limit = 3)
            if (parts.size != 3) return null
            val mcc = parts[0].trim().toIntOrNull() ?: return null
            val mnc = parts[1].trim().toIntOrNull() ?: return null
            val displayName = parts[2].trim()
            if (displayName.isEmpty()) return null
            return OperatorRow(mcc, mnc, displayName)
        }

        private fun formatCode(mcc: Int, mnc: Int): String {
            val mncFormat = if (mnc >= 100) "%03d" else "%02d"
            return "%03d-${mncFormat}".format(Locale.US, mcc, mnc)
        }
    }

    private data class OperatorCode(val mcc: Int, val mnc: Int)
    private data class OperatorRow(val mcc: Int, val mnc: Int, val displayName: String)
}
