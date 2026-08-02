package com.yugahashimoto.andcode.core.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Whether this app may read the device's own files, and how to ask for it.
 *
 * Two different permissions answer the same question depending on the platform: Android 11 dropped
 * broad filesystem access behind `MANAGE_EXTERNAL_STORAGE`, a special permission granted from a
 * system settings screen rather than a dialog, while older releases still honour the
 * `READ_EXTERNAL_STORAGE` runtime permission. Callers ask [isGranted] and, when it is false, follow
 * [needsSettingsScreen] to the right way of requesting it.
 */
class DeviceStorageAccess(
    private val context: Context,
) {
    fun isGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** True when the grant lives on a settings screen instead of a runtime permission dialog. */
    fun needsSettingsScreen(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The runtime permissions to request on the releases that still use them. */
    fun runtimePermissions(): Array<String> =
        if (needsSettingsScreen()) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /**
     * The settings screen that grants all-files access.
     *
     * The per-app screen is what the user wants, but a handful of devices ship without it, so the
     * caller can fall back to the full app list rather than crashing on a missing activity.
     */
    fun settingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )

    fun settingsFallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /**
     * The storage the sandbox may be given, or [DeviceStorage.Mounts.None] while access is not
     * granted. Emulated storage is checked for readability rather than assumed: a device with no
     * shared volume mounted reports a path that is not there.
     */
    fun mounts(): DeviceStorage.Mounts {
        if (!isGranted()) return DeviceStorage.Mounts.None
        val shared = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        val volumes = File("/storage")
        return DeviceStorage.Mounts(
            sharedStorage = shared?.takeIf { it.isDirectory },
            volumes = volumes.takeIf { it.isDirectory },
        )
    }
}
