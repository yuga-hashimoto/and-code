package com.yugahashimoto.andcode.feature.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Who currently owns interactive control of the shared Guest Browser / inline preview. */
enum class BrowserControlOwner {
    AGENT,
    USER_TAKEOVER,
    AGENT_HANDOFF,
}

/**
 * The app side of human/agent control sharing in the Guest Browser and inline preview.
 *
 * The agent asks for the user when it cannot do something itself, signing in above all: the user
 * completes it in the visible browser, then hands control back. The user can also take over at any
 * time and hand control back when ready. The agent polls or checks the workspace's status file, not
 * this object, so the two processes share nothing but the file protocol.
 *
 * [active] only drives the agent-requested handoff banner; it is null once the agent has control
 * again. A handoff that the user never answers is left to the agent's timeout rather than cleared
 * here, because leaving the screen (rotation included) must not look like a hand-back.
 */
object BrowserHandoff {
    /** The agent-requested handoff waiting on the user, or null while no handoff is pending. */
    var active by mutableStateOf<Session?>(null)
        private set

    /** Whether the user has manually taken control from the agent. */
    var userHasControl by mutableStateOf(false)
        private set

    /** Whether the current chat session's agent is actively running. */
    var agentWorking by mutableStateOf(false)
        private set

    private var boundStatusFile: File? = null
    private var userControlStatusFile: File? = null

    /** Current owner of the shared browser/preview surface. */
    val controlOwner: BrowserControlOwner
        get() =
            when {
                active != null -> BrowserControlOwner.AGENT_HANDOFF
                userHasControl -> BrowserControlOwner.USER_TAKEOVER
                else -> BrowserControlOwner.AGENT
            }

    /** Associates the active workspace's status file for user-initiated takeovers. */
    fun bindWorkspaceStatusFile(statusFile: File?) {
        boundStatusFile = statusFile
    }

    /** Reflects whether the agent is currently running in the active session. */
    fun updateAgentWorking(working: Boolean) {
        agentWorking = working
    }

    /** Shows the handoff banner after the watcher has recorded `awaiting_user` in the workspace. */
    fun begin(statusFile: File) {
        userHasControl = false
        userControlStatusFile = null
        active = Session(statusFile)
    }

    /** The user takes control proactively so the agent pauses browser mutations until handed back. */
    fun takeOver(statusFile: File? = boundStatusFile) {
        if (active != null) return
        userControlStatusFile = statusFile
        userHasControl = true
        if (statusFile != null) {
            writeBrowserStatus(statusFile, BROWSER_STATUS_USER)
        }
    }

    /** The user finished operating the page; the agent may continue. */
    fun handBack() {
        val handoffSession = active
        if (handoffSession != null) {
            writeBrowserStatus(handoffSession.statusFile, BROWSER_STATUS_AGENT)
            active = null
        }
        if (userHasControl) {
            userControlStatusFile?.let { writeBrowserStatus(it, BROWSER_STATUS_AGENT) }
            userHasControl = false
            userControlStatusFile = null
        }
    }

    /** The user declined to continue or closed the preview; clear active control holds. */
    fun dismiss() {
        val handoffSession = active
        if (handoffSession != null) {
            writeBrowserStatus(handoffSession.statusFile, BROWSER_STATUS_DISMISSED)
            active = null
        }
        if (userHasControl) {
            userControlStatusFile?.let { writeBrowserStatus(it, BROWSER_STATUS_AGENT) }
            userHasControl = false
            userControlStatusFile = null
        }
    }

    class Session internal constructor(
        val statusFile: File,
    )
}
