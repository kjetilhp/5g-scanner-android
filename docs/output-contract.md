# Output Contract

The Android app should emit logs compatible with the Node/TypeScript scanner.

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

Android devices may omit some signal metrics. When the app implementation begins, decide whether Android-specific partial readings require a compatible extension or a stricter filtering step before log emission.
