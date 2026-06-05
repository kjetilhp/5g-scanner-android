# Output Contract

The Android app should record coverage samples compatible with the Node/TypeScript scanner.

The reference project is expected at:

```text
external/node-scanner/
```

## Format

Logs are append-only JSON Lines:

```text
one CoverageSample JSON object per line
```

The reference writer currently serializes each sample with `JSON.stringify(sample)` followed by a newline.

## Sample Kinds

The current reference union is:

```text
CoverageSample =
  ServingSample |
  NeighbourSample |
  ScanSample
```

### Serving Sample

```text
kind: "serving"
fix: Fix
cell: Cell
```

### Neighbour Sample

```text
kind: "neighbour"
fix: Fix
cells: Cell[]
```

### Scan Sample

```text
kind: "scan"
fix: Fix
cells: Cell[]
durationMs: number
```

## Fix

```text
timestamp: ISO-8601 string
gpsTime: ISO-8601 string | null
lat: number
lon: number
altitude: number
speed?: number
heading?: number
hdop: number
satellites: number
```

Android may not expose all values directly. Prefer omitting optional values when unavailable and using explicit nullable fields only where the reference contract already does.

## Cell

The current reference cell union is:

```text
Cell = LteCell | Nr5gCell
```

### LTE

```text
rat: "LTE"
mcc: number
mnc: number
cellId: string
tac: number
pci: number
earfcn: number
band: number
signal: Signal
```

### 5G NR

```text
rat: "NR5G-SA" | "NR5G-NSA"
mcc: number
mnc: number
cellId: string
tac: number
pci: number
arfcn: number
band: number
signal: Signal
```

## Signal

```text
rsrp: number
rsrq: number
sinr: number
rssi?: number
```

Android stores `lat` and `lon` as collected in the internal sample JSON. UI display and CSV export round coordinates to five decimal places.

Android devices may omit some signal metrics. When the app implementation begins, decide whether Android-specific partial readings require a compatible extension or a stricter filtering step before sample persistence.

## Future Metadata Questions

The current JSONL contract intentionally keeps each sample small and close to the Node scanner reference. Before extending it, review whether repeated per-row metadata should move behind a compact sensor concept:

- `sensorId` on each sample, with sensor/device metadata reported separately or resolved server-side.
- Sensor type or platform, such as Android phone, Celerway router, Waveshare modem/card, or another collector.
- Device make/model class and OS/platform version.
- Collector app/version.
- Multi-radio metadata for Android and modem/router devices: radio source count, optional non-sensitive `radioSourceIndex`, default-data flag where applicable, and subscription carrier display label for UI/debug use.

Carrier metadata needs care because MCC/MNC and cell identity already identify the observed network, while carrier names and subscription labels can be device-specific, user-visible, localized, or unstable. Android subscription IDs and SIM slots should remain private to the collector and should not be persisted, reported, or exported. The Android sampler may produce one serving sample per active radio source for the same GNSS fix, but the current exported JSONL shape does not yet include radio-source metadata.
