package no.politiet.pit.encoding

import no.politiet.pit.domain.Cell
import no.politiet.pit.domain.CoordinatePrecision
import no.politiet.pit.domain.CoverageSample
import no.politiet.pit.domain.Fix
import no.politiet.pit.domain.LteCell
import no.politiet.pit.domain.NeighbourSample
import no.politiet.pit.domain.Nr5gCell
import no.politiet.pit.domain.ScanSample
import no.politiet.pit.domain.ServingSample
import no.politiet.pit.domain.Signal
import org.json.JSONArray
import org.json.JSONObject

object CoverageSampleJsonEncoder {
    fun encode(sample: CoverageSample): String {
        val json = JSONObject()
            .put("kind", sample.kind)
            .put("fix", sample.fix.toJson())

        when (sample) {
            is ServingSample -> json.put("cell", sample.cell.toJson())
            is NeighbourSample -> json.put("cells", sample.cells.toJsonArray())
            is ScanSample -> json
                .put("cells", sample.cells.toJsonArray())
                .put("durationMs", sample.durationMs)
        }

        return json.toString()
    }

    private fun Fix.toJson(): JSONObject {
        val json = JSONObject()
            .put("timestamp", timestamp.toString())
            .put("gpsTime", gpsTime?.toString() ?: JSONObject.NULL)
            .put("lat", CoordinatePrecision.rounded(lat))
            .put("lon", CoordinatePrecision.rounded(lon))
            .put("altitude", altitude)
            .put("hdop", hdop)
            .put("satellites", satellites)

        speed?.let { json.put("speed", it) }
        heading?.let { json.put("heading", it) }
        return json
    }

    private fun List<Cell>.toJsonArray(): JSONArray =
        JSONArray().also { cells ->
            forEach { cell -> cells.put(cell.toJson()) }
        }

    private fun Cell.toJson(): JSONObject {
        val json = JSONObject()
            .put("rat", rat)
            .put("mcc", mcc)
            .put("mnc", mnc)
            .put("cellId", cellId)
            .put("tac", tac)
            .put("pci", pci)
            .put("band", band)
            .put("signal", signal.toJson())

        when (this) {
            is LteCell -> json.put("earfcn", earfcn)
            is Nr5gCell -> json.put("arfcn", arfcn)
        }

        return json
    }

    private fun Signal.toJson(): JSONObject {
        val json = JSONObject()
            .put("rsrp", rsrp)
            .put("rsrq", rsrq)
            .put("sinr", sinr)

        rssi?.let { json.put("rssi", it) }
        return json
    }
}
