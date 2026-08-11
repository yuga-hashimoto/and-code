package com.yugahashimoto.andcode.core.diagnostics

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Why a run the app still believes is working has produced nothing for a while.
 *
 * A background turn that quietly dies looks exactly like one that is thinking hard: the composer
 * keeps its stop button, the drawer keeps its spinner, and nothing ever says otherwise. These are
 * the causes the app can actually tell apart from the outside, ordered from "the run is over" to
 * "it may still be working".
 */
enum class StallReason {
    /** The runtime does not answer at all, so whatever it was doing is out of reach. */
    RUNTIME_UNREACHABLE,

    /** The provider recorded an error on the turn and nothing ran after it. */
    PROVIDER_ERROR,

    /** The turn finished, but the completion never reached the app. */
    COMPLETION_MISSED,

    /** The turn is blocked on a permission request nobody answered. */
    AWAITING_PERMISSION,

    /** The turn is blocked on a question nobody answered. */
    AWAITING_QUESTION,

    /** The event stream is down, so progress may be happening unseen. */
    STREAM_DISCONNECTED,

    /** A tool call has been in flight for the whole silent stretch. */
    TOOL_RUNNING,

    /** Nothing above matched: the runtime accepted the turn and is producing nothing. */
    NO_OUTPUT,
}

/**
 * The verdict on a quiet run: [reason] says what to tell the user, [detail] carries the runtime's
 * own words for it (a provider error, the tool that is running, the connection failure).
 */
data class StallDiagnosis(
    val reason: StallReason,
    val detail: String? = null,
    /** How long the run has produced nothing the app could see. */
    val silentForMillis: Long = 0L,
) {
    /**
     * True when the diagnosis settles the run rather than only describing it. The caller acts on
     * these — recovering the transcript, surfacing the error — instead of showing a "still quiet"
     * warning.
     *
     * This and [isStopped] are groupings for presentation. What a caller *does* about a stall is
     * decided by branching on [reason] itself, so a new [StallReason] has to be placed in those
     * `when`s whatever these say about it.
     */
    val isTerminal: Boolean
        get() = reason == StallReason.PROVIDER_ERROR || reason == StallReason.COMPLETION_MISSED

    /**
     * True when the evidence points at a run that is over, out of reach, or producing nothing at
     * all — as opposed to one with a visible thing left to wait for.
     *
     * [StallReason.NO_OUTPUT] belongs here: it is reached only once a running tool, a pending
     * approval, a question and a dead stream have all been ruled out, which leaves a runtime
     * claiming to work with nothing to show for it. A long build is reported as
     * [StallReason.TOOL_RUNNING] instead, and stays out of the colour of a failure.
     */
    val isStopped: Boolean
        get() = isTerminal || reason == StallReason.RUNTIME_UNREACHABLE || reason == StallReason.NO_OUTPUT
}

/** What a caller could learn about a quiet run before asking for a diagnosis. */
data class StallEvidence(
    val silentForMillis: Long,
    val runtimeReachable: Boolean = true,
    /** Why the runtime could not be reached, when it could not. */
    val runtimeError: String? = null,
    val streamConnected: Boolean = true,
    val streamError: String? = null,
    val awaitingPermission: Boolean = false,
    val awaitingQuestion: Boolean = false,
    val transcript: RunSignals = RunSignals(),
)

/** The state of the last turn as recorded in the transcript the runtime persisted. */
data class RunSignals(
    /**
     * The provider recorded a failure on the turn. Kept apart from [providerError] because the
     * failure is what ends the run, while its text is only what there is to show for it — an error
     * carrying neither a message nor a name still means the turn is over.
     */
    val providerFailed: Boolean = false,
    /** The failure the provider recorded on the turn, in the runtime's own words. */
    val providerError: String? = null,
    /** Name (or title) of the tool call the transcript still shows as in flight. */
    val runningTool: String? = null,
    /** The runtime marked the last assistant turn complete. */
    val turnCompleted: Boolean = false,
)

/**
 * Names the most specific cause the evidence supports.
 *
 * The order is deliberate: an unreachable runtime makes every other signal stale, a recorded
 * failure or completion means the run is already over, and only once those are ruled out is a
 * blocked or merely slow turn worth reporting.
 */
fun diagnoseStall(evidence: StallEvidence): StallDiagnosis {
    val reason: StallReason
    val detail: String?
    when {
        !evidence.runtimeReachable -> {
            reason = StallReason.RUNTIME_UNREACHABLE
            detail = evidence.runtimeError
        }
        evidence.transcript.providerFailed -> {
            reason = StallReason.PROVIDER_ERROR
            detail = evidence.transcript.providerError
        }
        evidence.transcript.turnCompleted -> {
            reason = StallReason.COMPLETION_MISSED
            detail = null
        }
        evidence.awaitingPermission -> {
            reason = StallReason.AWAITING_PERMISSION
            detail = null
        }
        evidence.awaitingQuestion -> {
            reason = StallReason.AWAITING_QUESTION
            detail = null
        }
        !evidence.streamConnected -> {
            reason = StallReason.STREAM_DISCONNECTED
            detail = evidence.streamError
        }
        evidence.transcript.runningTool != null -> {
            reason = StallReason.TOOL_RUNNING
            detail = evidence.transcript.runningTool
        }
        else -> {
            reason = StallReason.NO_OUTPUT
            detail = null
        }
    }
    return StallDiagnosis(reason = reason, detail = detail, silentForMillis = evidence.silentForMillis)
}

/**
 * Whether an event is evidence that the run itself is getting somewhere, and so should restart the
 * clock a stall is measured against.
 *
 * Metadata events are deliberately excluded. A session's title being rewritten says nothing about
 * the turn, and `session.status: busy` is the runtime repeating a claim the watchdog exists to
 * doubt — a runtime that re-announced a wedged run would otherwise keep the stall from ever being
 * reported. The start of a run resets the clock through the running-state transition instead.
 */
fun OpenCodeEvent.provesRunProgress(): Boolean =
    when (this) {
        is OpenCodeEvent.MessageUpdated,
        is OpenCodeEvent.MessagePartUpdated,
        is OpenCodeEvent.MessagePartDelta,
        is OpenCodeEvent.PermissionAsked,
        is OpenCodeEvent.PermissionReplied,
        is OpenCodeEvent.QuestionAsked,
        is OpenCodeEvent.SessionIdle,
        is OpenCodeEvent.SessionError,
        -> true
        is OpenCodeEvent.SessionCreated,
        is OpenCodeEvent.SessionUpdated,
        is OpenCodeEvent.SessionStatusChanged,
        OpenCodeEvent.ServerConnected,
        is OpenCodeEvent.Unknown,
        -> false
    }

/**
 * Reads the last turn of a transcript.
 *
 * Only the final message matters: it is the one the quiet run belongs to. A transcript whose last
 * message is the user's own prompt means the runtime has not started answering at all, which the
 * empty signals below describe on their own.
 */
fun inspectRun(messages: List<OpenCodeMessage>): RunSignals {
    val last = messages.lastOrNull() ?: return RunSignals()
    if (last.info.role != "assistant") return RunSignals()
    val failure = last.info.error
    // A stopped turn is recorded as an error too. It is over, whatever else the transcript still
    // shows: a tool call the stop left sitting in `running` is not something to report as working.
    if (failure?.isAbort == true) return RunSignals(turnCompleted = true)
    val runningTool =
        last.parts
            .lastOrNull { part -> part.type == "tool" && part.state?.status() in IN_FLIGHT_TOOL_STATUSES }
            ?.let { part -> part.state?.get("title")?.stringOrNull()?.takeIf(String::isNotBlank) ?: part.tool }
    return RunSignals(
        providerFailed = failure != null,
        providerError = failure?.let { it.message ?: it.name },
        runningTool = runningTool,
        // A turn holding an unfinished tool call is not complete, whatever its timestamps say.
        turnCompleted = runningTool == null && last.info.time.completed != null,
    )
}

private val IN_FLIGHT_TOOL_STATUSES = setOf("running", "pending")

private fun Map<String, JsonElement>.status(): String? = get("status")?.stringOrNull()

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
