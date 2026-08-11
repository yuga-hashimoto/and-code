package com.yugahashimoto.andcode.core.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.core.diagnostics.StallDiagnosis
import com.yugahashimoto.andcode.core.diagnostics.explain
import com.yugahashimoto.andcode.runtime.PermissionResponse

class RuntimeNotificationHelper(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        ensureChannels()
    }

    fun notifyPermission(
        request: PermissionRequest,
        chatTitle: String?,
        runtimeId: String,
    ) {
        if (!canPostNotifications()) return
        val openIntent =
            pendingActivityIntent(
                requestCode = request.id.hashCode(),
                extras =
                    mapOf(
                        EXTRA_OPEN_ACTIVITY to true,
                        EXTRA_TARGET_SESSION_ID to request.sessionId,
                        EXTRA_RUNTIME_ID to runtimeId,
                    ),
            )
        val allowOnce = permissionActionIntent(request, runtimeId, PermissionResponse.ONCE, remember = false)
        val allowAlways = permissionActionIntent(request, runtimeId, PermissionResponse.ALWAYS, remember = true)
        val reject = permissionActionIntent(request, runtimeId, PermissionResponse.REJECT, remember = false)

        val notification =
            NotificationCompat.Builder(context, CHANNEL_APPROVALS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    context.getString(R.string.notification_approval_title) + " · " +
                        (chatTitle?.takeIf(String::isNotBlank) ?: context.getString(R.string.new_chat)),
                )
                .setContentText(request.permission)
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        buildString {
                            append(chatTitle?.takeIf(String::isNotBlank) ?: context.getString(R.string.new_chat))
                            append('\n')
                            append(request.permission)
                            if (request.patterns.isNotEmpty()) {
                                append('\n')
                                append(request.patterns.joinToString("\n"))
                            }
                        },
                    ),
                )
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, context.getString(R.string.allow_once), allowOnce)
                .addAction(0, context.getString(R.string.always_allow), allowAlways)
                .addAction(0, context.getString(R.string.reject), reject)
                .build()

        safeNotify(permissionNotificationId(request.id), notification)
    }

    fun notifyQuestion(
        request: QuestionRequest,
        chatTitle: String?,
        runtimeId: String,
    ) {
        if (!canPostNotifications()) return
        val openIntent =
            pendingActivityIntent(
                requestCode = ("question:" + request.id).hashCode(),
                extras =
                    mapOf(
                        EXTRA_OPEN_CHAT to true,
                        EXTRA_TARGET_SESSION_ID to request.sessionId,
                        EXTRA_RUNTIME_ID to runtimeId,
                    ),
            )
        val prompt = request.questions.firstOrNull()?.question?.takeIf(String::isNotBlank)
        val notification =
            NotificationCompat.Builder(context, CHANNEL_APPROVALS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    context.getString(R.string.notification_question_title) + " · " +
                        (chatTitle?.takeIf(String::isNotBlank) ?: context.getString(R.string.new_chat)),
                )
                .setContentText(prompt ?: context.getString(R.string.notification_question_body))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        buildString {
                            append(chatTitle?.takeIf(String::isNotBlank) ?: context.getString(R.string.new_chat))
                            if (prompt != null) {
                                append('\n')
                                append(prompt)
                            }
                        },
                    ),
                )
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

        safeNotify(questionNotificationId(request.id), notification)
    }

    fun notifySessionComplete(
        sessionId: String,
        chatTitle: String?,
        runtimeId: String,
    ) {
        cancelStalled(sessionId)
        if (!canPostNotifications()) return
        val intent =
            pendingActivityIntent(
                requestCode = ("complete:" + sessionId).hashCode(),
                extras =
                    mapOf(
                        EXTRA_OPEN_CHAT to true,
                        EXTRA_TARGET_SESSION_ID to sessionId,
                        EXTRA_RUNTIME_ID to runtimeId,
                    ),
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_complete_title))
                .setContentText(
                    context.getString(
                        R.string.notification_complete_body,
                        chatTitle?.takeIf(String::isNotBlank) ?: context.getString(R.string.new_chat),
                    ),
                )
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
        safeNotify(statusNotificationId(sessionId, "done"), notification)
    }

    fun notifySessionError(
        sessionId: String?,
        message: String?,
        runtimeId: String,
    ) {
        sessionId?.let(::cancelStalled)
        if (!canPostNotifications()) return
        val intent =
            pendingActivityIntent(
                requestCode = ("error:" + (sessionId ?: "error")).hashCode(),
                extras =
                    mapOf(
                        EXTRA_OPEN_ACTIVITY to true,
                        EXTRA_TARGET_SESSION_ID to (sessionId.orEmpty()),
                        EXTRA_RUNTIME_ID to runtimeId,
                    ),
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_error_title))
                .setContentText(message ?: context.getString(R.string.notification_error_body))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
        safeNotify(statusNotificationId(sessionId ?: "error", "err"), notification)
    }

    /**
     * Tells the user that a run they left working has stopped producing anything, and what the app
     * managed to find out about why. Tapping it opens the chat so they can stop or resend.
     */
    fun notifySessionStalled(
        sessionId: String,
        chatTitle: String?,
        diagnosis: StallDiagnosis,
        runtimeId: String,
    ) {
        if (!canPostNotifications()) return
        val intent =
            pendingActivityIntent(
                requestCode = ("stalled:$sessionId").hashCode(),
                extras =
                    mapOf(
                        EXTRA_OPEN_CHAT to true,
                        EXTRA_TARGET_SESSION_ID to sessionId,
                        EXTRA_RUNTIME_ID to runtimeId,
                    ),
            )
        val reason = diagnosis.explain(context)
        val title = chatTitle?.takeIf(String::isNotBlank) ?: context.getString(R.string.new_chat)
        val notification =
            NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_stalled_title))
                .setContentText(context.getString(R.string.notification_stalled_body, title, reason))
                // Every stall notice carries the same title, so the chat has to be named in the
                // expanded text too or two of them are indistinguishable once opened.
                .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$reason"))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
        safeNotify(statusNotificationId(sessionId, "stalled"), notification)
    }

    /**
     * Takes down a stall notice once the run it described has resolved. Without it, "this run has
     * gone quiet" would sit in the shade next to the completion notice for the same chat.
     */
    fun cancelStalled(sessionId: String) {
        manager.cancel(statusNotificationId(sessionId, "stalled"))
    }

    fun cancelPermission(permissionId: String) {
        manager.cancel(permissionNotificationId(permissionId))
    }

    fun cancelQuestion(questionId: String) {
        manager.cancel(questionNotificationId(questionId))
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun safeNotify(
        id: Int,
        notification: Notification,
    ) {
        if (!canPostNotifications()) return
        runCatching { manager.notify(id, notification) }
    }

    private fun permissionActionIntent(
        request: PermissionRequest,
        runtimeId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): PendingIntent {
        val intent =
            Intent(context, PermissionActionReceiver::class.java).apply {
                action = ACTION_PERMISSION_RESPONSE
                putExtra(EXTRA_SESSION_ID, request.sessionId)
                putExtra(EXTRA_RUNTIME_ID, runtimeId)
                putExtra(EXTRA_PERMISSION_ID, request.id)
                putExtra(EXTRA_PERMISSION_RESPONSE, response.apiValue)
                putExtra(EXTRA_PERMISSION_REMEMBER, remember)
            }
        val requestCode = (request.id + response.apiValue).hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pendingActivityIntent(
        requestCode: Int,
        extras: Map<String, Any>,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                extras.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putExtra(key, value)
                        is String -> putExtra(key, value)
                        is Int -> putExtra(key, value)
                    }
                }
            }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVALS,
                context.getString(R.string.notification_channel_approvals),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.notification_channel_status),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    // A narrow hash window made unrelated sessions collide onto one notification id, so one chat's
    // notice visibly replaced another's and tapping it carried the wrong content intent. The wider
    // mask makes that collision vanishingly unlikely.
    private fun permissionNotificationId(permissionId: String): Int =
        NOTIFICATION_ID_BASE_PERMISSION + (permissionId.hashCode() and NOTIFICATION_ID_MASK)

    private fun questionNotificationId(questionId: String): Int =
        NOTIFICATION_ID_BASE_QUESTION + (questionId.hashCode() and NOTIFICATION_ID_MASK)

    private fun statusNotificationId(
        sessionId: String,
        kind: String,
    ): Int = NOTIFICATION_ID_BASE_STATUS + ((sessionId + kind).hashCode() and NOTIFICATION_ID_MASK)

    companion object {
        const val CHANNEL_APPROVALS = "opencode_approvals"
        const val CHANNEL_STATUS = "opencode_status"
        const val ACTION_PERMISSION_RESPONSE = "com.yugahashimoto.andcode.PERMISSION_RESPONSE"

        private const val NOTIFICATION_ID_MASK = 0x000FFFFF
        private const val NOTIFICATION_ID_BASE_PERMISSION = 1_000_000
        private const val NOTIFICATION_ID_BASE_QUESTION = 3_000_000
        private const val NOTIFICATION_ID_BASE_STATUS = 5_000_000
        const val EXTRA_OPEN_ACTIVITY = "open_activity"
        const val EXTRA_OPEN_CHAT = "open_chat"
        const val EXTRA_SESSION_ID = "session_id"

        /** The key [com.yugahashimoto.andcode.MainActivity] reads to jump straight to a session. */
        const val EXTRA_TARGET_SESSION_ID = "target_session_id"
        const val EXTRA_RUNTIME_ID = "runtime_id"
        const val EXTRA_PERMISSION_ID = "permission_id"
        const val EXTRA_PERMISSION_RESPONSE = "permission_response"
        const val EXTRA_PERMISSION_REMEMBER = "permission_remember"
    }
}
