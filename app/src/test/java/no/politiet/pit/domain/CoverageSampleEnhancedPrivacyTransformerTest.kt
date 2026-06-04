package no.politiet.pit.domain

import no.politiet.pit.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CoverageSampleEnhancedPrivacyTransformerTest {
    @Test
    fun snapsTimeLocationAndGnssDetails() {
        val sample = ServingSample(
            fix = Fix(
                timestamp = Instant.parse("2026-06-04T14:37:22Z"),
                gpsTime = Instant.parse("2026-06-04T14:37:20Z"),
                lat = 59.913923,
                lon = 10.752245,
                altitude = 127.4,
                speed = 8.2,
                heading = 183.0,
                hdop = 0.8,
                satellites = 12,
            ),
            cell = LteCell(
                mcc = 242,
                mnc = 1,
                cellId = "abc",
                tac = 42,
                pci = 7,
                earfcn = 1650,
                band = 3,
                signal = Signal(rsrp = -90, rsrq = -12, rssi = -62, sinr = 14),
            ),
        )

        val reduced = CoverageSampleEnhancedPrivacyTransformer.reduce(sample) as ServingSample
        val fix = reduced.fix

        assertEquals(Instant.parse("2026-06-04T00:00:00Z"), fix.timestamp)
        assertEquals(Instant.parse("2026-06-04T00:00:00Z"), fix.gpsTime)
        assertTrue(fix.lat != sample.fix.lat)
        assertTrue(fix.lon != sample.fix.lon)
        assertEquals(150.0, fix.altitude, 0.0)
        assertNull(fix.speed)
        assertNull(fix.heading)
        assertEquals(AppConfig.EnhancedPrivacy.reportedHdop, fix.hdop, 0.0)
        assertEquals(AppConfig.EnhancedPrivacy.reportedSatellites, fix.satellites)
    }
}
