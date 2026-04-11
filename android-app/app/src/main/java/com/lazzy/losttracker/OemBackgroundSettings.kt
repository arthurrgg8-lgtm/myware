package com.lazzy.losttracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object OemBackgroundSettings {
    private fun manufacturer(): String = Build.MANUFACTURER.trim().lowercase()

    fun shouldPrompt(): Boolean {
        val maker = manufacturer()
        return maker.contains("xiaomi") ||
            maker.contains("redmi") ||
            maker.contains("poco") ||
            maker.contains("vivo") ||
            maker.contains("iqoo") ||
            maker.contains("oppo") ||
            maker.contains("realme") ||
            maker.contains("oneplus") ||
            maker.contains("huawei") ||
            maker.contains("honor")
    }

    fun createIntent(context: Context): Intent? {
        val packageManager = context.packageManager
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val candidates = when {
            isVivoLike() -> listOf(
                explicitIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                explicitIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                explicitIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
            )
            isXiaomiLike() -> listOf(
                explicitIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                explicitIntent("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            )
            isColorOsLike() -> listOf(
                explicitIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                explicitIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                explicitIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            )
            isHuaweiLike() -> listOf(
                explicitIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                explicitIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            else -> emptyList()
        }

        return candidates.firstOrNull { it.resolveActivity(packageManager) != null }
            ?: appDetails.takeIf { it.resolveActivity(packageManager) != null }
    }

    private fun explicitIntent(packageName: String, className: String): Intent {
        return Intent().apply {
            component = ComponentName(packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun isVivoLike(): Boolean {
        val maker = manufacturer()
        return maker.contains("vivo") || maker.contains("iqoo")
    }

    private fun isXiaomiLike(): Boolean {
        val maker = manufacturer()
        return maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")
    }

    private fun isColorOsLike(): Boolean {
        val maker = manufacturer()
        return maker.contains("oppo") || maker.contains("realme") || maker.contains("oneplus")
    }

    private fun isHuaweiLike(): Boolean {
        val maker = manufacturer()
        return maker.contains("huawei") || maker.contains("honor")
    }
}
