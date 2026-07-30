package com.yugahashimoto.andcode.feature.chat

/**
 * A single row of the chat timeline: a user bubble, assistant body text, or a collapsed run of
 * reasoning/tool/patch parts that the user can expand to inspect.
 *
 * Ids are namespaced by kind so they stay unique as `LazyColumn` keys across the whole transcript.
 */
sealed interface TimelineEntry {
    val id: String

    data class UserMessage(val message: ChatMessage) : TimelineEntry {
        override val id: String get() = "user:${message.id}"
    }

    data class Body(val messageId: String, val part: ChatPart.Text) : TimelineEntry {
        override val id: String get() = "body:${part.id}"
    }

    data class Image(val messageId: String, val part: ChatPart.Image) : TimelineEntry {
        override val id: String get() = "image:${part.id}"
    }

    data class Activity(override val id: String, val parts: List<ChatPart>) : TimelineEntry

    data class Todo(override val id: String, val todos: List<TodoItem>) : TimelineEntry

    /**
     * Trailing meta row for a finished assistant turn: the clock time the reply landed plus how
     * long the user waited for it, surfaced LINE-style beneath the answer.
     */
    data class Footer(
        override val id: String,
        val completedAt: Long,
        val durationMs: Long,
    ) : TimelineEntry
}

/** Broad buckets used to summarise a run of tool calls in one line. */
enum class ToolCategory { COMMAND, READ, EDIT, SUBAGENT, OTHER }

/**
 * Counts per category plus the part that is currently in flight, if any.
 *
 * While a run is still executing we surface the running step by name instead of a count, so the
 * user can see what the agent is doing right now.
 */
data class ActivitySummary(
    val counts: Map<ToolCategory, Int>,
    val reasoningCount: Int,
    val running: ChatPart.Tool?,
    val hasError: Boolean,
) {
    val isEmpty: Boolean
        get() = counts.isEmpty() && reasoningCount == 0
}

/**
 * Flattens the transcript into rows, collapsing consecutive reasoning/tool/patch parts into a
 * single [TimelineEntry.Activity] and keeping body text as its own entry so the narrative order of
 * the answer is preserved.
 *
 * Grouping spans messages on purpose. OpenCode opens a new assistant message per model step, so a
 * turn that calls tools arrives as several messages — `[read, read]`, then `[reasoning, text]`.
 * Grouping inside a single message would leave those as two separate rows even though nothing
 * separates them on screen, which is exactly what a collapsed run is meant to avoid. Only a user
 * message or non-blank body text ends a run.
 *
 * Blank text parts do not split a run: the stream emits an empty text part before the assistant
 * starts writing, and treating it as a separator would break every run into single-step groups.
 */
fun groupConversationTimeline(messages: List<ChatMessage>): List<TimelineEntry> {
    val entries = mutableListOf<TimelineEntry>()
    val pending = mutableListOf<ChatPart>()
    var lastUserAt: Long? = null
    var turnStartAt: Long? = null
    var turnCompletedAt: Long? = null
    var turnLastId: String? = null

    fun flush() {
        if (pending.isEmpty()) return
        entries += TimelineEntry.Activity("activity:${pending.first().id}", pending.toList())
        pending.clear()
    }

    fun flushTurnFooter() {
        val completed = turnCompletedAt ?: return
        val lastId = turnLastId ?: return
        val start = lastUserAt ?: turnStartAt ?: completed
        entries +=
            TimelineEntry.Footer(
                id = "footer:$lastId",
                completedAt = completed,
                durationMs = (completed - start).coerceAtLeast(0L),
            )
        turnStartAt = null
        turnCompletedAt = null
        turnLastId = null
    }

    messages.forEach { message ->
        if (message.isUser) {
            flush()
            flushTurnFooter()
            lastUserAt = message.timestamp
            entries += TimelineEntry.UserMessage(message)
            return@forEach
        }
        if (turnStartAt == null) turnStartAt = message.timestamp
        turnLastId = message.id
        message.completedAt?.let { completed ->
            turnCompletedAt = maxOf(turnCompletedAt ?: 0L, completed)
        }
        message.parts.forEach { part ->
            when {
                part is ChatPart.Tool && part.name == "todowrite" && part.todos.isNotEmpty() -> {
                    flush()
                    entries += TimelineEntry.Todo("todo:${part.id}", part.todos)
                }
                part is ChatPart.Image -> {
                    flush()
                    entries += TimelineEntry.Image(message.id, part)
                }
                part !is ChatPart.Text -> pending += part
                part.text.isNotBlank() -> {
                    flush()
                    entries += TimelineEntry.Body(message.id, part)
                }
                else -> Unit
            }
        }
    }
    flush()
    flushTurnFooter()
    return entries
}

/**
 * Re-resolves an activity group by id against the current messages.
 *
 * The detail sheet holds only the group id rather than a captured list, so a run that is still
 * executing keeps streaming new steps into the open sheet. Group ids are the id of the run's first
 * part, so they stay stable as the run grows — see [groupConversationTimeline].
 */
fun findActivityParts(
    messages: List<ChatMessage>,
    groupId: String,
): List<ChatPart> =
    groupConversationTimeline(messages)
        .filterIsInstance<TimelineEntry.Activity>()
        .firstOrNull { it.id == groupId }
        ?.parts
        .orEmpty()

fun summarizeActivity(parts: List<ChatPart>): ActivitySummary {
    val counts = mutableMapOf<ToolCategory, Int>()
    var reasoning = 0
    var running: ChatPart.Tool? = null
    var hasError = false

    parts.forEach { part ->
        when (part) {
            is ChatPart.Reasoning -> reasoning++
            is ChatPart.Patch -> counts.increment(ToolCategory.EDIT)
            is ChatPart.Tool -> {
                counts.increment(part.name.toToolCategory())
                if (part.status == ToolStatus.ERROR) hasError = true
                if (running == null && (part.status == ToolStatus.RUNNING || part.status == ToolStatus.PENDING)) {
                    running = part
                }
            }
            is ChatPart.Text -> Unit
            is ChatPart.Image -> Unit
        }
    }
    return ActivitySummary(counts = counts, reasoningCount = reasoning, running = running, hasError = hasError)
}

fun String.toToolCategory(): ToolCategory =
    when (lowercase()) {
        "bash", "shell" -> ToolCategory.COMMAND
        "read", "glob", "grep", "list", "webfetch" -> ToolCategory.READ
        "edit", "write", "patch", "multiedit" -> ToolCategory.EDIT
        "task" -> ToolCategory.SUBAGENT
        else -> ToolCategory.OTHER
    }

private fun MutableMap<ToolCategory, Int>.increment(category: ToolCategory) {
    this[category] = (this[category] ?: 0) + 1
}
