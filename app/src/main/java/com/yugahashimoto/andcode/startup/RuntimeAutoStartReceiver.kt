package com.yugahashimoto.andcode.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yugahashimoto.andcode.AndCodeApplication

/** Restores the local runtime after Android terminates it during a reboot or APK replacement. */
class RuntimeAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!isRuntimeAutoStartBroadcast(intent.action)) return
        val app = context.applicationContext as? AndCodeApplication ?: return
        RuntimeAutoStartInitializer.restoreIfConfigured(app)
    }
}

internal fun isRuntimeAutoStartBroadcast(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
