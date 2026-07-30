package com.yugahashimoto.andcode.feature.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

object AssistantStatus {
    fun isActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            }
        }

        val configuredService =
            Settings.Secure.getString(
                context.contentResolver,
                VOICE_INTERACTION_SERVICE_SETTING,
            )
        val expected = ComponentName(context, AndCodeVoiceInteractionService::class.java)
        return isConfiguredService(configuredService, expected)
    }

    internal fun isConfiguredService(
        configuredService: String?,
        expected: ComponentName,
    ): Boolean =
        matchesConfiguredService(
            configuredService,
            expected.flattenToString(),
            expected.flattenToShortString(),
        )

    internal fun matchesConfiguredService(
        configuredService: String?,
        flattenedName: String,
        shortName: String,
    ): Boolean = configuredService == flattenedName || configuredService == shortName

    private const val VOICE_INTERACTION_SERVICE_SETTING = "voice_interaction_service"
}
