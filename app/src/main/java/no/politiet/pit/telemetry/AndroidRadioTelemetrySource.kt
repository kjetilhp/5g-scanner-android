@file:Suppress("DEPRECATION")

package no.politiet.pit.telemetry

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import no.politiet.pit.domain.LteCell
import no.politiet.pit.domain.Nr5gCell
import no.politiet.pit.domain.Nr5gMode
import no.politiet.pit.domain.Signal
import java.time.Instant

class AndroidRadioTelemetrySource(
    private val context: Context,
) : RadioTelemetrySource {
    private val baseTelephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
    private val registrations = mutableListOf<RadioRegistration>()
    private val latestBySubscription = mutableMapOf<Int, RadioTelemetry>()
    private var onImportantRadioChange: () -> Unit = {}

    override fun start(onImportantRadioChange: () -> Unit) {
        this.onImportantRadioChange = onImportantRadioChange
        if (registrations.isNotEmpty()) {
            stopRegistrations(clearTelemetry = false)
        }
        if (baseTelephonyManager == null) {
            Log.w(TAG, "Android radio telemetry unavailable: no TelephonyManager")
            return
        }

        runCatching {
            synchronized(latestBySubscription) {
                latestBySubscription.clear()
            }
            registrations += activeRegistrations()
            registrations.forEach { registration ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startTelephonyCallback(registration)
                } else {
                    startLegacyListener(registration)
                }
                refreshCellInfo(registration)
            }
            Log.i(TAG, "Android radio telemetry started: subscriptions=${registrations.size}")
        }.onFailure { error ->
            Log.w(TAG, "Could not start Android radio telemetry", error)
        }
    }

    override fun stop() {
        stopRegistrations(clearTelemetry = true)
        onImportantRadioChange = {}
    }

    private fun stopRegistrations(clearTelemetry: Boolean) {
        registrations.forEach { registration ->
            runCatching {
                registration.callback?.let(registration.manager::unregisterTelephonyCallback)
                registration.callback = null
                registration.legacyListener?.let { registration.manager.listen(it, PhoneStateListener.LISTEN_NONE) }
                registration.legacyListener = null
            }.onFailure { error ->
                Log.w(TAG, "Could not stop Android radio telemetry for radioSource=${registration.radioSourceIndex}", error)
            }
        }
        registrations.clear()
        if (clearTelemetry) {
            synchronized(latestBySubscription) {
                latestBySubscription.clear()
            }
        }
    }

    override fun latest(sampleNumber: Int, capturedAt: Instant): RadioTelemetry? {
        if (latestBySubscription.isEmpty()) {
            registrations.forEach(::refreshCellInfo)
        }
        return latestAll(sampleNumber, capturedAt).firstOrNull()
    }

    override fun latestAll(sampleNumber: Int, capturedAt: Instant): List<RadioTelemetry> {
        if (latestBySubscription.isEmpty()) {
            registrations.forEach(::refreshCellInfo)
        }
        return synchronized(latestBySubscription) {
            latestBySubscription.values
                .sortedWith(compareByDescending<RadioTelemetry> { it.isDefaultDataSubscription }
                    .thenBy { it.radioSourceIndex })
        }
    }

    @SuppressLint("MissingPermission")
    private fun activeRegistrations(): List<RadioRegistration> {
        val manager = baseTelephonyManager ?: return emptyList()
        val defaultDataSubscriptionId = SubscriptionManager.getDefaultDataSubscriptionId()
        val activeSubscriptions = runCatching {
            subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        }.getOrElse { error ->
            Log.w(TAG, "Could not read active subscriptions; falling back to default subscription", error)
            emptyList()
        }

        if (activeSubscriptions.isEmpty()) {
            return listOf(RadioRegistration(
                manager = manager,
                radioSourceIndex = 0,
                subscriptionId = null,
                simSlotIndex = null,
                carrierName = null,
                isDefaultDataSubscription = true,
            ))
        }

        return activeSubscriptions
            .sortedWith(compareBy({ it.simSlotIndex }, { it.subscriptionId }))
            .mapIndexed { index, subscription ->
                val subscriptionId = subscription.subscriptionId
                RadioRegistration(
                    manager = manager.createForSubscriptionId(subscriptionId),
                    radioSourceIndex = index,
                    subscriptionId = subscriptionId,
                    simSlotIndex = subscription.simSlotIndex,
                    carrierName = subscription.carrierName?.toString(),
                    isDefaultDataSubscription = subscriptionId == defaultDataSubscriptionId,
                )
            }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun startTelephonyCallback(registration: RadioRegistration) {
        if (registration.callback != null) return
        val radioCallback = RadioCallback(registration)
        registration.callback = radioCallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registration.manager.registerTelephonyCallback(
                TelephonyManager.INCLUDE_LOCATION_DATA_FINE,
                context.mainExecutor,
                radioCallback,
            )
        } else {
            registration.manager.registerTelephonyCallback(context.mainExecutor, radioCallback)
        }
        Log.i(TAG, "Android radio telemetry callback registered: radioSource=${registration.radioSourceIndex}")
    }

    private fun startLegacyListener(registration: RadioRegistration) {
        if (registration.legacyListener != null) return
        val listener = LegacyRadioListener(registration)
        registration.legacyListener = listener
        registration.manager.listen(
            listener,
            PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                PhoneStateListener.LISTEN_CELL_INFO or
                PhoneStateListener.LISTEN_SERVICE_STATE,
        )
        Log.i(TAG, "Android radio telemetry legacy listener registered: radioSource=${registration.radioSourceIndex}")
    }

    @SuppressLint("MissingPermission")
    private fun refreshCellInfo(registration: RadioRegistration) {
        runCatching {
            registration.manager.requestCellInfoUpdate(
                context.mainExecutor,
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        handleCellInfo(registration, cellInfo, importantChangeMaySampleSoon = false)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        Log.d(TAG, "Cell info update failed: radioSource=${registration.radioSourceIndex}, errorCode=$errorCode", detail)
                    }
                },
            )
        }.onFailure { error ->
            Log.d(TAG, "Could not request cell info update for radioSource=${registration.radioSourceIndex}", error)
        }
    }

    private fun handleCellInfo(
        registration: RadioRegistration,
        cellInfo: List<CellInfo>,
        importantChangeMaySampleSoon: Boolean,
    ) {
        val telemetry = cellInfo
            .filter { it.isRegistered }
            .mapNotNull { toRadioTelemetry(registration, it) }
            .maxByOrNull { radioPriority(it) }
            ?: return

        val newServingCellKey = servingCellKey(telemetry)
        val servingCellChanged = registration.latestServingCellKey != null &&
            registration.latestServingCellKey != newServingCellKey
        registration.latestServingCellKey = newServingCellKey
        synchronized(latestBySubscription) {
            latestBySubscription[registration.key] = telemetry
        }
        Log.d(TAG, "Android radio telemetry updated: radioSource=${registration.radioSourceIndex}, servingCell=$newServingCellKey")
        if (importantChangeMaySampleSoon && servingCellChanged) {
            onImportantRadioChange()
        }
    }

    private fun handleSignalStrength(registration: RadioRegistration, signalStrength: SignalStrength) {
        registration.latestSignalStrength = signalStrength
        refreshCellInfo(registration)
    }

    private fun handleServiceState(registration: RadioRegistration, serviceState: ServiceState) {
        val changed = registration.latestServiceState?.state != serviceState.state ||
            registration.latestServiceState?.roaming != serviceState.roaming
        registration.latestServiceState = serviceState
        if (changed) {
            onImportantRadioChange()
        }
    }

    private fun handleDisplayInfo(registration: RadioRegistration, displayInfo: TelephonyDisplayInfo) {
        val changed = registration.latestDisplayInfo?.overrideNetworkType != displayInfo.overrideNetworkType ||
            registration.latestDisplayInfo?.networkType != displayInfo.networkType
        registration.latestDisplayInfo = displayInfo
        if (changed) {
            onImportantRadioChange()
        }
    }

    private fun toRadioTelemetry(registration: RadioRegistration, cellInfo: CellInfo): RadioTelemetry? =
        when (cellInfo) {
            is CellInfoNr -> cellInfo.toRadioTelemetry(registration)
            is CellInfoLte -> cellInfo.toRadioTelemetry(registration)
            else -> null
        }

    private fun CellInfoLte.toRadioTelemetry(registration: RadioRegistration): RadioTelemetry? {
        val identity = cellIdentity
        val signalStrength = cellSignalStrength
        val mcc = identity.mcc() ?: return null
        val mnc = identity.mnc() ?: return null
        val signal = signalStrength.signal() ?: return null
        return RadioTelemetry(
            receivedAt = Instant.now(),
            radioSourceIndex = registration.radioSourceIndex,
            subscriptionCarrierName = registration.carrierName,
            isDefaultDataSubscription = registration.isDefaultDataSubscription,
            servingCell = LteCell(
                mcc = mcc,
                mnc = mnc,
                cellId = identity.ci.toString(),
                tac = identity.tac,
                pci = identity.pci,
                earfcn = identity.earfcn,
                band = identity.firstBandOrUnknown(),
                signal = signal,
            ),
        )
    }

    private fun CellInfoNr.toRadioTelemetry(registration: RadioRegistration): RadioTelemetry? {
        val identity = cellIdentity as? CellIdentityNr ?: return null
        val signalStrength = cellSignalStrength as? CellSignalStrengthNr ?: return null
        val mcc = identity.mcc() ?: return null
        val mnc = identity.mnc() ?: return null
        val signal = signalStrength.signal() ?: return null
        return RadioTelemetry(
            receivedAt = Instant.now(),
            radioSourceIndex = registration.radioSourceIndex,
            subscriptionCarrierName = registration.carrierName,
            isDefaultDataSubscription = registration.isDefaultDataSubscription,
            servingCell = Nr5gCell(
                mode = nrMode(registration),
                mcc = mcc,
                mnc = mnc,
                cellId = identity.nci.toString(),
                tac = identity.tac,
                pci = identity.pci,
                arfcn = identity.nrarfcn,
                band = identity.firstBandOrUnknown(),
                signal = signal,
            ),
        )
    }

    private fun CellSignalStrengthLte.signal(): Signal? {
        val rsrp = rsrp.available() ?: dbm.available() ?: return null
        val rsrq = rsrq.available() ?: return null
        val sinr = rssnr.available() ?: return null
        return Signal(
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            rssi = rssi.available(),
        )
    }

    private fun CellSignalStrengthNr.signal(): Signal? {
        val rsrp = ssRsrp.available() ?: csiRsrp.available() ?: dbm.available() ?: return null
        val rsrq = ssRsrq.available() ?: csiRsrq.available() ?: return null
        val sinr = ssSinr.available() ?: csiSinr.available() ?: return null
        return Signal(
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            rssi = null,
        )
    }

    private fun CellIdentityLte.mcc(): Int? = mccString?.toIntOrNull()
    private fun CellIdentityLte.mnc(): Int? = mncString?.toIntOrNull()
    private fun CellIdentityNr.mcc(): Int? = mccString?.toIntOrNull()
    private fun CellIdentityNr.mnc(): Int? = mncString?.toIntOrNull()

    private fun CellIdentityLte.firstBandOrUnknown(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bands.firstOrNull() ?: UNKNOWN_BAND else UNKNOWN_BAND

    private fun CellIdentityNr.firstBandOrUnknown(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bands.firstOrNull() ?: UNKNOWN_BAND else UNKNOWN_BAND

    private fun Int.available(): Int? =
        takeUnless { it == CellInfo.UNAVAILABLE || it == Int.MAX_VALUE || it == UNKNOWN_BAND }

    private fun nrMode(registration: RadioRegistration): Nr5gMode =
        if (registration.latestDisplayInfo?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA) {
            Nr5gMode.NonStandalone
        } else {
            Nr5gMode.Standalone
        }

    private fun servingCellKey(telemetry: RadioTelemetry): String =
        with(telemetry.servingCell) {
            "${telemetry.radioSourceIndex}:$rat:$mcc:$mnc:$tac:$cellId:$pci"
        }

    private fun radioPriority(telemetry: RadioTelemetry): Int =
        when (telemetry.servingCell) {
            is Nr5gCell -> 2
            is LteCell -> 1
        }

    @TargetApi(Build.VERSION_CODES.S)
    private inner class RadioCallback(
        private val registration: RadioRegistration,
    ) : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.CellInfoListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DisplayInfoListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            handleSignalStrength(registration, signalStrength)
        }

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            handleCellInfo(registration, cellInfo, importantChangeMaySampleSoon = true)
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            handleServiceState(registration, serviceState)
        }

        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            handleDisplayInfo(registration, telephonyDisplayInfo)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private inner class LegacyRadioListener(
        private val registration: RadioRegistration,
    ) : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            handleSignalStrength(registration, signalStrength)
        }

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            handleCellInfo(registration, cellInfo, importantChangeMaySampleSoon = true)
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            handleServiceState(registration, serviceState)
        }
    }

    private data class RadioRegistration(
        val manager: TelephonyManager,
        val radioSourceIndex: Int,
        val subscriptionId: Int?,
        val simSlotIndex: Int?,
        val carrierName: String?,
        val isDefaultDataSubscription: Boolean,
        var callback: TelephonyCallback? = null,
        var legacyListener: PhoneStateListener? = null,
        var latestSignalStrength: SignalStrength? = null,
        var latestDisplayInfo: TelephonyDisplayInfo? = null,
        var latestServiceState: ServiceState? = null,
        var latestServingCellKey: String? = null,
    ) {
        val key: Int = subscriptionId ?: DEFAULT_SUBSCRIPTION_KEY
    }

    private companion object {
        const val TAG = "5GScanner"
        const val UNKNOWN_BAND = 0
        const val DEFAULT_SUBSCRIPTION_KEY = Int.MIN_VALUE
    }
}
