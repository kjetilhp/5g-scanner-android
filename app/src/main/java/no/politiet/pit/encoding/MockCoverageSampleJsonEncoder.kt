package no.politiet.pit.encoding

import no.politiet.pit.domain.GnssMode
import no.politiet.pit.telemetry.MockTelemetry
import org.json.JSONObject
import java.time.Instant

object MockCoverageSampleJsonEncoder {
    fun encode(
        sampleNumber: Int,
        capturedAt: Instant,
        telemetry: MockTelemetry,
    ): String {
        val wave = sampleNumber % 24
        val fix = JSONObject()
            .put("timestamp", capturedAt.toString())
            .put("gpsTime", JSONObject.NULL)
            .put("lat", 59.9139 + wave * 0.00008)
            .put("lon", 10.7522 + wave * 0.00011)
            .put("altitude", 23.0 + (wave % 5))
            .put("speed", if (telemetry.gnssMode == GnssMode.HighAccuracy) 1.6 else 0.8)
            .put("heading", (sampleNumber * 17) % 360)
            .put("hdop", telemetry.hdop.toDouble())
            .put("satellites", if (telemetry.gnssMode == GnssMode.HighAccuracy) 18 else 11)

        val signal = JSONObject()
            .put("rsrp", telemetry.rsrp)
            .put("rsrq", telemetry.rsrq)
            .put("sinr", telemetry.sinr)
            .put("rssi", telemetry.rsrp + 30)

        val cell = JSONObject()
            .put("rat", "LTE")
            .put("mcc", 242)
            .put("mnc", 1)
            .put("cellId", "mock-${100000 + sampleNumber}")
            .put("tac", 4100 + (sampleNumber % 12))
            .put("pci", 120 + (sampleNumber % 48))
            .put("earfcn", 6300 + (sampleNumber % 20))
            .put("band", 20)
            .put("signal", signal)

        return JSONObject()
            .put("kind", "serving")
            .put("fix", fix)
            .put("cell", cell)
            .toString()
    }
}
