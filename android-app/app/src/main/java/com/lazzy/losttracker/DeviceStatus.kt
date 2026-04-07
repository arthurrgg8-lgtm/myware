package com.lazzy.losttracker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.time.Instant
import java.time.ZoneId

data class DeviceSnapshot(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val networkStatus: String,
    val wifiSsid: String?,
    val carrierName: String?,
    val localIp: String?,
    val deviceTime: String,
    val deviceTimeZone: String,
    val deviceTimestampMs: Long,
)

object DeviceStatus {
    fun read(context: Context): DeviceSnapshot {
        return read(context, includeExtendedNetworkDetails = true)
    }

    fun readMinimal(context: Context): DeviceSnapshot {
        return read(context, includeExtendedNetworkDetails = false)
    }

    private fun read(context: Context, includeExtendedNetworkDetails: Boolean): DeviceSnapshot {
        val now = currentDeviceClock()
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryLevel = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else 0
        val chargingStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            chargingStatus == BatteryManager.BATTERY_STATUS_FULL

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
            ?: return DeviceSnapshot(
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                networkStatus = "offline",
                wifiSsid = null,
                carrierName = null,
                localIp = null,
                deviceTime = now.isoTime,
                deviceTimeZone = now.timeZone,
                deviceTimestampMs = now.epochMs,
            )
        val caps = connectivityManager.getNetworkCapabilities(network)
            ?: return DeviceSnapshot(
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                networkStatus = "offline",
                wifiSsid = null,
                carrierName = null,
                localIp = null,
                deviceTime = now.isoTime,
                deviceTimeZone = now.timeZone,
                deviceTimestampMs = now.epochMs,
            )
        val networkStatus = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "online"
        }
        val wifiSsid = if (includeExtendedNetworkDetails && networkStatus == "wifi") {
            readWifiSsid(context, caps)
        } else {
            null
        }
        val carrierName = if (includeExtendedNetworkDetails) readCarrierName(context) else null
        val localIp = if (includeExtendedNetworkDetails) readLocalIp(connectivityManager, network) else null
        return DeviceSnapshot(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkStatus = networkStatus,
            wifiSsid = wifiSsid,
            carrierName = carrierName,
            localIp = localIp,
            deviceTime = now.isoTime,
            deviceTimeZone = now.timeZone,
            deviceTimestampMs = now.epochMs,
        )
    }

    fun currentDeviceTime(): String = currentDeviceClock().isoTime

    private data class DeviceClockSnapshot(
        val isoTime: String,
        val timeZone: String,
        val epochMs: Long,
    )

    private fun currentDeviceClock(): DeviceClockSnapshot {
        val now = Instant.now()
        return DeviceClockSnapshot(
            isoTime = now.toString(),
            timeZone = ZoneId.systemDefault().id,
            epochMs = now.toEpochMilli(),
        )
    }

    private fun readWifiSsid(context: Context, caps: NetworkCapabilities): String? {
        val transportInfo = caps.transportInfo
        val ssidFromTransport = (transportInfo as? WifiInfo)?.ssid
        val ssid = sanitizeSsid(ssidFromTransport)
        if (ssid != null) {
            return ssid
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return sanitizeSsid(wifiManager?.connectionInfo?.ssid)
    }

    private fun sanitizeSsid(raw: String?): String? {
        val value = raw?.trim()?.removePrefix("\"")?.removeSuffix("\"")
        if (value.isNullOrBlank() || value == WifiManager.UNKNOWN_SSID || value == "<unknown ssid>") {
            return null
        }
        return value
    }

    private fun readCarrierName(context: Context): String? {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)

        val subscriptionName = runCatching {
            val activeDataId = SubscriptionManager.getActiveDataSubscriptionId()
            val activeInfo = subscriptionManager?.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == activeDataId }
                ?: subscriptionManager?.activeSubscriptionInfoList?.firstOrNull()
            activeInfo?.carrierName?.toString()?.trim()
        }.getOrNull()
        if (!subscriptionName.isNullOrBlank()) {
            return subscriptionName
        }

        val simName = telephonyManager?.simOperatorName?.trim()
        if (!simName.isNullOrBlank()) {
            return simName
        }

        val networkName = telephonyManager?.networkOperatorName?.trim()
        if (!networkName.isNullOrBlank()) {
            return networkName
        }

        val mappedName = mapOperatorCodeToCarrier(
            telephonyManager?.simOperator?.trim(),
            telephonyManager?.networkOperator?.trim()
        )
        if (mappedName != null) {
            return mappedName
        }

        return null
    }

    private fun mapOperatorCodeToCarrier(vararg operatorCodes: String?): String? {
        val knownCarriers = mapOf(
            "42901" to "NTC",
            "42902" to "Ncell",
            "42903" to "Smart Cell"
        )
        for (code in operatorCodes) {
            val normalized = code?.trim()
            if (!normalized.isNullOrBlank()) {
                knownCarriers[normalized]?.let { return it }
            }
        }
        return null
    }

    private fun readLocalIp(
        connectivityManager: ConnectivityManager,
        network: android.net.Network
    ): String? {
        return runCatching {
            connectivityManager.getLinkProperties(network)
                ?.linkAddresses
                ?.asSequence()
                ?.map(LinkAddress::getAddress)
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
