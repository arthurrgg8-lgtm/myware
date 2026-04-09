package com.lazzy.losttracker

import android.os.Build

data class DeviceCompatibilityProfile(
    val name: String,
    val commandPollIntervalMs: Long,
    val heartbeatIntervalMs: Long,
    val watchdogIntervalMs: Long,
    val syncWakeLockTimeoutMs: Long,
    val useTransientSyncWakeLock: Boolean,
    val guidance: String,
)

object DeviceCompatibility {
    private fun manufacturer(): String {
        return Build.MANUFACTURER.trim().lowercase()
    }

    fun currentProfile(): DeviceCompatibilityProfile {
        val maker = manufacturer()
        return when {
            maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") -> {
                DeviceCompatibilityProfile(
                    name = "MIUI/HyperOS",
                    commandPollIntervalMs = 2_000L,
                    heartbeatIntervalMs = 12_000L,
                    watchdogIntervalMs = 20_000L,
                    syncWakeLockTimeoutMs = 20_000L,
                    useTransientSyncWakeLock = true,
                    guidance = "Enable battery unrestricted mode and allow auto-start for best background reliability."
                )
            }
            maker.contains("vivo") || maker.contains("iqoo") -> {
                DeviceCompatibilityProfile(
                    name = "Vivo",
                    commandPollIntervalMs = 2_000L,
                    heartbeatIntervalMs = 12_000L,
                    watchdogIntervalMs = 20_000L,
                    syncWakeLockTimeoutMs = 20_000L,
                    useTransientSyncWakeLock = true,
                    guidance = "Allow background activity, disable battery restrictions, and keep the app allowed in iManager."
                )
            }
            maker.contains("oppo") || maker.contains("realme") || maker.contains("oneplus") -> {
                DeviceCompatibilityProfile(
                    name = "ColorOS",
                    commandPollIntervalMs = 2_000L,
                    heartbeatIntervalMs = 12_000L,
                    watchdogIntervalMs = 20_000L,
                    syncWakeLockTimeoutMs = 20_000L,
                    useTransientSyncWakeLock = true,
                    guidance = "Set battery use to unrestricted and allow background running in app management settings."
                )
            }
            maker.contains("huawei") || maker.contains("honor") -> {
                DeviceCompatibilityProfile(
                    name = "EMUI",
                    commandPollIntervalMs = 2_000L,
                    heartbeatIntervalMs = 12_000L,
                    watchdogIntervalMs = 20_000L,
                    syncWakeLockTimeoutMs = 20_000L,
                    useTransientSyncWakeLock = true,
                    guidance = "Disable battery launch restrictions and allow this app to run automatically in the background."
                )
            }
            maker.contains("samsung") -> {
                DeviceCompatibilityProfile(
                    name = "Samsung",
                    commandPollIntervalMs = 2_000L,
                    heartbeatIntervalMs = 15_000L,
                    watchdogIntervalMs = 25_000L,
                    syncWakeLockTimeoutMs = 15_000L,
                    useTransientSyncWakeLock = true,
                    guidance = "Exclude the app from sleeping apps and battery restrictions for steadier command pickup."
                )
            }
            else -> {
                DeviceCompatibilityProfile(
                    name = "Standard",
                    commandPollIntervalMs = BuildConfig.COMMAND_POLL_INTERVAL_MS,
                    heartbeatIntervalMs = BuildConfig.HEARTBEAT_INTERVAL_MS,
                    watchdogIntervalMs = BuildConfig.WATCHDOG_INTERVAL_MS,
                    syncWakeLockTimeoutMs = 12_000L,
                    useTransientSyncWakeLock = false,
                    guidance = "Battery optimization exemption improves reliability on devices that restrict background work."
                )
            }
        }
    }
}
