package no.politiet.pit.domain

data class Signal(
    val rsrp: Int,
    val rsrq: Int,
    val sinr: Int,
    val rssi: Int?,
)
