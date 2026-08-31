package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage

/** Why a run that never settled was given up on. */
enum class ScheduleRunTimeout {
    /** Nothing was heard from the run for a long time; it is not coming back. */
    IDLE,

    /** The run kept going past the point where it can still be called a scheduled run. */
    MAX_DURATION,
}

/**
 * The session an event reports progress on, or null when it says nothing about any one session.
 *
 * A run is only given up on when nothing has happened for a long while, so this decides what
 * "something happened" means: an event naming the run's own session, and nothing else. A
 * neighbouring chat's tool call must never keep a dead run alive.
 */
fun progressSessionIdOf(event: OpenCodeEvent): String? =
    when (event) {
        is OpenCodeEvent.MessageUpdated -> event.info.sessionId
        is OpenCodeEvent.MessagePartUpdated -> event.part.sessionId
        is OpenCodeEvent.MessagePartDelta -> event.sessionId
        is OpenCodeEvent.PermissionAsked -> event.request.sessionId
        is OpenCodeEvent.PermissionReplied -> event.sessionId
        is OpenCodeEvent.QuestionAsked -> event.request.sessionId
        is OpenCodeEvent.SessionIdle -> event.sessionId
        is OpenCodeEvent.SessionStatusChanged -> event.sessionId
        is OpenCodeEvent.SessionUpdated -> event.session.id
        is OpenCodeEvent.SessionError -> event.sessionId
        // A subagent the run spawned is the run working, so its birth counts for the parent.
        is OpenCodeEvent.SessionCreated -> event.session.parentId
        OpenCodeEvent.ServerConnected -> null
        is OpenCodeEvent.Unknown -> null
    }

/**
 * Whether a run that has not settled yet should be given up on.
 *
 * The old rule was a flat cap on the whole run, which failed every schedule whose work honestly
 * takes longer than the cap - a prompt that writes twenty articles never stood a chance. What
 * actually distinguishes a dead run from a long one is silence, so the cap applies to the gap
 * since the last sign of life; the total duration only guards against a run that never ends.
 */
fun scheduleRunTimeout(
    elapsedMs: Long,
    sinceProgressMs: Long,
    idleTimeoutMs: Long,
    maxDurationMs: Long,
): ScheduleRunTimeout? =
    when {
        elapsedMs >= maxDurationMs -> ScheduleRunTimeout.MAX_DURATION
        sinceProgressMs >= idleTimeoutMs -> ScheduleRunTimeout.IDLE
        else -> null
    }

/**
 * A value that changes whenever the transcript has moved on, and stays put while it has not.
 *
 * This is the only sign of life left once the event stream drops, so it has to notice more than a
 * new message: a single long turn grows by parts, by the text streaming into one of them, and by a
 * tool part's state as the tool works. A run doing any of that is working, not stalled.
 *
 * The parts go in by hash rather than spelled out, because a tool part carries its whole output in
 * `state` and the mark is only ever compared against the previous one. It has to be the values and
 * not the shape: a tool that runs for an hour keeps the same state keys the whole time and moves
 * only what is under them, so counting keys would call the busiest kind of run idle.
 */
fun transcriptProgressMarkOf(messages: List<OpenCodeMessage>): String {
    val newest = messages.lastOrNull()
    val newestInfo = newest?.info
    return listOf(
        messages.size,
        newestInfo?.id,
        newestInfo?.time?.completed,
        newest?.parts?.size,
        newest?.parts?.hashCode(),
    ).joinToString(":")
}
