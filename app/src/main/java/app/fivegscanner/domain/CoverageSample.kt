package app.fivegscanner.domain

sealed interface CoverageSample {
    val kind: String
    val fix: Fix
}

data class ServingSample(
    override val fix: Fix,
    val cell: Cell,
) : CoverageSample {
    override val kind: String = "serving"
}

data class NeighbourSample(
    override val fix: Fix,
    val cells: List<Cell>,
) : CoverageSample {
    override val kind: String = "neighbour"
}

data class ScanSample(
    override val fix: Fix,
    val cells: List<Cell>,
    val durationMs: Long,
) : CoverageSample {
    override val kind: String = "scan"
}
