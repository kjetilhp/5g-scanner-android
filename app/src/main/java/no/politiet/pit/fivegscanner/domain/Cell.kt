package no.politiet.pit.fivegscanner.domain

sealed interface Cell {
    val rat: String
    val mcc: Int
    val mnc: Int
    val cellId: String
    val tac: Int
    val pci: Int
    val band: Int
    val signal: Signal
}

data class LteCell(
    override val mcc: Int,
    override val mnc: Int,
    override val cellId: String,
    override val tac: Int,
    override val pci: Int,
    val earfcn: Int,
    override val band: Int,
    override val signal: Signal,
) : Cell {
    override val rat: String = "LTE"
}

data class Nr5gCell(
    val mode: Nr5gMode,
    override val mcc: Int,
    override val mnc: Int,
    override val cellId: String,
    override val tac: Int,
    override val pci: Int,
    val arfcn: Int,
    override val band: Int,
    override val signal: Signal,
) : Cell {
    override val rat: String = mode.contractValue
}

enum class Nr5gMode(val contractValue: String) {
    Standalone("NR5G-SA"),
    NonStandalone("NR5G-NSA"),
}
