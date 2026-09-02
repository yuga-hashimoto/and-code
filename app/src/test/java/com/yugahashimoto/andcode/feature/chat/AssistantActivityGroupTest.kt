package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActivityGroupTest {
    private fun tool(
        id: String,
        name: String,
        status: ToolStatus = ToolStatus.COMPLETED,
        title: String? = null,
        todos: List<TodoItem> = emptyList(),
    ) = ChatPart.Tool(id = id, name = name, status = status, title = title, todos = todos)

    private fun assistant(
        id: String,
        vararg parts: ChatPart,
    ) = ChatMessage(id = id, isUser = false, parts = parts.toList())

    @Test
    fun `collapses a consecutive run of tools into one activity entry`() {
        val entries =
            groupConversationTimeline(
                listOf(
                    assistant(
                        "m1",
                        tool("t1", "bash"),
                        ChatPart.Reasoning("r1", "thinking"),
                        tool("t2", "read"),
                    ),
                ),
            )

        assertEquals(1, entries.size)
        val activity = entries.single() as TimelineEntry.Activity
        assertEquals(3, activity.parts.size)
    }

    @Test
    fun `collapses a run that spans several assistant messages`() {
        // OpenCode opens a new assistant message per model step, so a turn that reads two files
        // and then thinks arrives as two messages. It is still one uninterrupted run on screen.
        val entries =
            groupConversationTimeline(
                listOf(
                    ChatMessage(id = "m0", isUser = true, parts = listOf(ChatPart.Text("u1", "explain this repo"))),
                    assistant("m1", tool("t1", "read"), tool("t2", "read")),
                    assistant("m2", ChatPart.Reasoning("r1", "thinking"), ChatPart.Text("x1", "This repo is…")),
                ),
            )

        assertEquals(3, entries.size)
        assertTrue(entries[0] is TimelineEntry.UserMessage)
        assertEquals(listOf("t1", "t2", "r1"), (entries[1] as TimelineEntry.Activity).parts.map { it.id })
        assertEquals("This repo is…", (entries[2] as TimelineEntry.Body).part.text)
    }

    @Test
    fun `a user message ends the preceding run`() {
        val entries =
            groupConversationTimeline(
                listOf(
                    assistant("m1", tool("t1", "read")),
                    ChatMessage(id = "m2", isUser = true, parts = listOf(ChatPart.Text("u1", "next"))),
                    assistant("m3", tool("t2", "read")),
                ),
            )

        assertEquals(listOf("activity:t1", "user:m2", "activity:t2"), entries.map { it.id })
    }

    @Test
    fun `body text splits activity into separate groups`() {
        val entries =
            groupConversationTimeline(
                listOf(
                    assistant(
                        "m1",
                        tool("t1", "bash"),
                        ChatPart.Text("x1", "Exploring the codebase."),
                        tool("t2", "edit"),
                        tool("t3", "write"),
                        ChatPart.Text("x2", "Done."),
                    ),
                ),
            )

        assertEquals(4, entries.size)
        assertEquals(listOf("t1"), (entries[0] as TimelineEntry.Activity).parts.map { it.id })
        assertEquals("Exploring the codebase.", (entries[1] as TimelineEntry.Body).part.text)
        assertEquals(listOf("t2", "t3"), (entries[2] as TimelineEntry.Activity).parts.map { it.id })
        assertEquals("Done.", (entries[3] as TimelineEntry.Body).part.text)
    }

    @Test
    fun `blank text parts do not split a run`() {
        val entries =
            groupConversationTimeline(
                listOf(
                    assistant(
                        "m1",
                        tool("t1", "bash"),
                        ChatPart.Text("x1", ""),
                        tool("t2", "bash"),
                    ),
                    assistant(
                        "m2",
                        ChatPart.Text("x2", "   "),
                        tool("t3", "bash"),
                    ),
                ),
            )

        assertEquals(1, entries.size)
        assertEquals(listOf("t1", "t2", "t3"), (entries.single() as TimelineEntry.Activity).parts.map { it.id })
    }

    @Test
    fun `activity id is the id of its first part so compose keys stay stable`() {
        val parts = listOf(tool("first", "bash"), tool("second", "read"))

        assertEquals("activity:first", groupConversationTimeline(listOf(assistant("m1", *parts.toTypedArray()))).single().id)
        // Appending to a growing run must not change the group identity — not even when the new
        // step lands in the next assistant message.
        assertEquals(
            "activity:first",
            groupConversationTimeline(
                listOf(assistant("m1", *parts.toTypedArray()), assistant("m2", tool("third", "read"))),
            ).single().id,
        )
    }

    @Test
    fun `counts tools by category and treats patch parts as edits`() {
        val summary =
            summarizeActivity(
                listOf(
                    tool("t1", "bash"),
                    tool("t2", "Bash"),
                    tool("t3", "read"),
                    tool("t4", "grep"),
                    tool("t5", "glob"),
                    tool("t6", "task"),
                    tool("t7", "todowrite"),
                    ChatPart.Patch("p1", listOf("A.kt")),
                    ChatPart.Reasoning("r1", "thinking"),
                ),
            )

        assertEquals(2, summary.counts[ToolCategory.COMMAND])
        assertEquals(3, summary.counts[ToolCategory.READ])
        assertEquals(1, summary.counts[ToolCategory.EDIT])
        assertEquals(1, summary.counts[ToolCategory.SUBAGENT])
        assertEquals(1, summary.counts[ToolCategory.OTHER])
        assertEquals(1, summary.reasoningCount)
        assertNull(summary.running)
    }

    @Test
    fun `surfaces the first in-flight tool while the run is still executing`() {
        val summary =
            summarizeActivity(
                listOf(
                    tool("t1", "bash"),
                    tool("t2", "bash", status = ToolStatus.RUNNING, title = "gh run watch"),
                    tool("t3", "read", status = ToolStatus.PENDING),
                ),
            )

        assertEquals("t2", summary.running?.id)
        assertEquals("gh run watch", summary.running?.title)
    }

    @Test
    fun `activity part keys stay unique when Claude repeats a call id`() {
        val parts = listOf(tool("call-1", "bash"), tool("call-1", "bash", status = ToolStatus.ERROR))

        assertEquals(
            listOf("activity-part:0:call-1", "activity-part:1:call-1"),
            parts.mapIndexed(::activityPartKey),
        )
    }

    @Test
    fun `flags a run that contains a failed tool`() {
        val summary = summarizeActivity(listOf(tool("t1", "bash"), tool("t2", "bash", status = ToolStatus.ERROR)))

        assertTrue(summary.hasError)
        assertNull(summary.running)
    }

    @Test
    fun `resolves an activity group by id across messages`() {
        val messages =
            listOf(
                ChatMessage(id = "m0", isUser = true, parts = listOf(ChatPart.Text("u1", "go"))),
                ChatMessage(
                    id = "m1",
                    isUser = false,
                    parts =
                        listOf(
                            tool("a1", "bash"),
                            ChatPart.Text("x1", "Now editing."),
                            tool("b1", "edit"),
                            tool("b2", "write"),
                        ),
                ),
            )

        assertEquals(listOf("b1", "b2"), findActivityParts(messages, "activity:b1").map { it.id })
        assertEquals(listOf("a1"), findActivityParts(messages, "activity:a1").map { it.id })
        assertTrue(findActivityParts(messages, "activity:nope").isEmpty())
    }

    @Test
    fun `resolving a group picks up steps appended while the run is still going`() {
        val growing =
            ChatMessage(id = "m1", isUser = false, parts = listOf(tool("a1", "bash"), tool("a2", "read")))

        assertEquals(listOf("a1", "a2"), findActivityParts(listOf(growing), "activity:a1").map { it.id })
    }

    @Test
    fun `reasoning-only run is not empty`() {
        val summary = summarizeActivity(listOf(ChatPart.Reasoning("r1", "thinking")))

        assertTrue(summary.counts.isEmpty())
        assertEquals(1, summary.reasoningCount)
        assertTrue(!summary.isEmpty)
    }

    @Test
    fun `todowrite with todos is extracted into a Todo entry`() {
        val todos =
            listOf(
                TodoItem("task 1", "completed", "high"),
                TodoItem("task 2", "in_progress", "medium"),
            )
        val entries =
            groupConversationTimeline(
                listOf(assistant("m1", tool("t1", "todowrite", todos = todos))),
            )

        assertEquals(1, entries.size)
        val todoEntry = entries.single() as TimelineEntry.Todo
        assertEquals("todo:t1", todoEntry.id)
        assertEquals(2, todoEntry.todos.size)
        assertEquals("task 1", todoEntry.todos[0].content)
    }

    @Test
    fun `todowrite with todos splits surrounding activity`() {
        val todos = listOf(TodoItem("task 1", "completed", "high"))
        val entries =
            groupConversationTimeline(
                listOf(
                    assistant(
                        "m1",
                        tool("t1", "bash"),
                        tool("t2", "todowrite", todos = todos),
                        tool("t3", "read"),
                    ),
                ),
            )

        assertEquals(3, entries.size)
        assertEquals(listOf("t1"), (entries[0] as TimelineEntry.Activity).parts.map { it.id })
        assertEquals("todo:t2", (entries[1] as TimelineEntry.Todo).id)
        assertEquals(listOf("t3"), (entries[2] as TimelineEntry.Activity).parts.map { it.id })
    }

    @Test
    fun `todowrite entries with a reused id get distinct entry ids`() {
        // Claude Code can reuse a tool_use call id (toolu_…) across retries, so two unrelated
        // todowrite updates can arrive with the same part id. Each occurrence must still get a
        // distinct TimelineEntry.Todo id or a bar the user dismissed for the first update would
        // stay suppressed for the second, unrelated one.
        val firstTodos = listOf(TodoItem("task 1", "completed", "high"))
        val secondTodos = listOf(TodoItem("task 2", "in_progress", "medium"))
        val entries =
            groupConversationTimeline(
                listOf(
                    assistant("m1", tool("t1", "todowrite", todos = firstTodos)),
                    assistant("m2", tool("t1", "todowrite", todos = secondTodos)),
                ),
            )

        assertEquals(2, entries.size)
        val firstEntry = entries[0] as TimelineEntry.Todo
        val secondEntry = entries[1] as TimelineEntry.Todo
        assertEquals("todo:t1", firstEntry.id)
        assertEquals("todo:t1:1", secondEntry.id)
        assertTrue(firstEntry.id != secondEntry.id)
    }

    @Test
    fun `todowrite without todos stays in activity group`() {
        val entries =
            groupConversationTimeline(
                listOf(assistant("m1", tool("t1", "todowrite"))),
            )

        assertEquals(1, entries.size)
        assertTrue(entries.single() is TimelineEntry.Activity)
    }

    @Test
    fun `image parts get their own entry and split the surrounding run`() {
        val entries =
            groupConversationTimeline(
                listOf(
                    assistant(
                        "m1",
                        tool("t1", "read"),
                        ChatPart.Image("i1", "image/png", "data:image/png;base64,abc"),
                        ChatPart.Text("x1", "see above"),
                    ),
                ),
            )

        assertEquals(3, entries.size)
        assertEquals(listOf("t1"), (entries[0] as TimelineEntry.Activity).parts.map { it.id })
        val image = entries[1] as TimelineEntry.Image
        assertEquals("i1", image.part.id)
        assertEquals("image:i1", image.id)
        assertEquals("see above", (entries[2] as TimelineEntry.Body).part.text)
    }

    @Test
    fun `activity part keys are unique when streamed parts reuse an id`() {
        val parts =
            listOf(
                tool("toolu_duplicate", "bash"),
                tool("toolu_duplicate", "read"),
            )

        val keys = parts.mapIndexed(::activityPartKey)

        assertEquals(parts.size, keys.toSet().size)
    }

    @Test
    fun `activity group ids stay unique when separate runs reuse the same first part id`() {
        // Claude Code reuses tool_use call ids (toolu_…) across retries, so two independent runs can
        // each begin with the very same part id. That must not produce duplicate LazyColumn keys.
        val entries =
            groupConversationTimeline(
                listOf(
                    assistant("m1", tool("toolu_retry", "bash")),
                    ChatMessage(id = "m2", isUser = true, parts = listOf(ChatPart.Text("u1", "try again"))),
                    assistant("m3", tool("toolu_retry", "bash")),
                ),
            )

        val activityIds = entries.filterIsInstance<TimelineEntry.Activity>().map { it.id }
        assertEquals(listOf("activity:toolu_retry", "activity:toolu_retry:1"), activityIds)
        assertEquals(activityIds.size, activityIds.toSet().size)
    }

    @Test
    fun `activity group id stays bare for the first run sharing a part id`() {
        val entries =
            groupConversationTimeline(
                listOf(
                    assistant("m1", tool("toolu_same", "bash")),
                    ChatMessage(id = "m2", isUser = true, parts = listOf(ChatPart.Text("u1", "again"))),
                    assistant("m3", tool("toolu_same", "bash")),
                ),
            )

        assertEquals("activity:toolu_same", (entries[0] as TimelineEntry.Activity).id)
    }

    @Test
    fun `activity group ids stay stable as a growing run streams in`() {
        // The first occurrence keeps the bare id, so an already-flushed group's identity survives a
        // sibling run that later reuses the same first part id.
        val first = listOf(assistant("m1", tool("toolu_stream", "bash")))
        val firstId = (groupConversationTimeline(first).single() as TimelineEntry.Activity).id
        assertEquals("activity:toolu_stream", firstId)

        val grown =
            first +
                listOf(
                    ChatMessage(id = "m2", isUser = true, parts = listOf(ChatPart.Text("u1", "retry"))),
                    assistant("m3", tool("toolu_stream", "bash")),
                )
        val grownIds = groupConversationTimeline(grown).filterIsInstance<TimelineEntry.Activity>().map { it.id }
        assertEquals(listOf("activity:toolu_stream", "activity:toolu_stream:1"), grownIds)
    }
}
