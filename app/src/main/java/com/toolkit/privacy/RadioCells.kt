package com.toolkit.privacy

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager

/** One visible cell tower, as the modem reports it. */
data class CellTower(val tech: String, val serving: Boolean, val dbm: Int, val detail: String) {
    val quality: Int get() = ((dbm + 120) * 100 / 70).coerceIn(0, 100)
}

object RadioCells {
    @SuppressLint("MissingPermission")
    fun read(ctx: Context): List<CellTower> {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return emptyList()
        return try {
            val raw: List<CellInfo> = tm.allCellInfo.orEmpty()
            raw.mapNotNull { ci ->
                val dbmOf = { v: Int -> if (v == Int.MAX_VALUE) -140 else v }
                when (ci) {
                    is CellInfoLte -> CellTower("4G LTE", ci.isRegistered, dbmOf(ci.cellSignalStrength.dbm), "RSRQ ${ci.cellSignalStrength.rsrq} · band/EARFCN ${ci.cellIdentity.earfcn} · PCI ${ci.cellIdentity.pci}")
                    is CellInfoWcdma -> CellTower("3G", ci.isRegistered, dbmOf(ci.cellSignalStrength.dbm), "UARFCN ${ci.cellIdentity.uarfcn} · PSC ${ci.cellIdentity.psc}")
                    is CellInfoGsm -> CellTower("2G", ci.isRegistered, dbmOf(ci.cellSignalStrength.dbm), "ARFCN ${ci.cellIdentity.arfcn} · BSIC ${ci.cellIdentity.bsic}")
                    else -> if (Build.VERSION.SDK_INT >= 29 && ci is CellInfoNr) CellTower("5G NR", ci.isRegistered, dbmOf(ci.cellSignalStrength.dbm), "NR cell") else null
                }
            }.sortedWith(compareByDescending<CellTower> { it.serving }.thenByDescending { it.dbm })
        } catch (e: Exception) { emptyList() }
    }

    /** Operator and network type of the SIM in use, when the phone shares it. */
    @SuppressLint("MissingPermission")
    fun operator(ctx: Context): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return "no telephony"
        val name = tm.networkOperatorName.orEmpty().ifBlank { "unknown operator" }
        val roam = if (tm.isNetworkRoaming) " · roaming" else ""
        return "$name (${tm.networkOperator.orEmpty().ifBlank { "?" }})$roam"
    }
}
