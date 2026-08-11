package com.yugahashimoto.andcode.data.repository

import android.content.Context
import com.yugahashimoto.andcode.R

/** User-visible labels produced while a runtime reports activity events. */
interface RuntimeActivityMessages {
    val streamConnectionFailed: String
    val eventConnectedTitle: String
    val eventConnectedDetail: String
    val eventTool: String
    val eventReasoning: String
    val eventPermission: String
    val eventCompleted: String
    val eventError: String
    val eventStalled: String
    val eventQuestion: String
    val eventUnknown: String

    companion object Default : RuntimeActivityMessages {
        override val streamConnectionFailed = "OpenCode event stream connection failed"
        override val eventConnectedTitle = "Event connection"
        override val eventConnectedDetail = "Connected to OpenCode real-time events"
        override val eventTool = "Tool execution"
        override val eventReasoning = "Reasoning"
        override val eventPermission = "Waiting for approval"
        override val eventCompleted = "Execution complete"
        override val eventError = "Execution error"
        override val eventStalled = "Run has gone quiet"
        override val eventQuestion = "Question"
        override val eventUnknown = "Unsupported event"
    }
}

class AndroidRuntimeActivityMessages(private val context: Context) : RuntimeActivityMessages {
    override val streamConnectionFailed get() = context.getString(R.string.activity_stream_connection_failed)
    override val eventConnectedTitle get() = context.getString(R.string.activity_event_connected_title)
    override val eventConnectedDetail get() = context.getString(R.string.activity_event_connected_detail)
    override val eventTool get() = context.getString(R.string.activity_event_tool)
    override val eventReasoning get() = context.getString(R.string.activity_event_reasoning)
    override val eventPermission get() = context.getString(R.string.activity_event_permission)
    override val eventCompleted get() = context.getString(R.string.activity_event_completed)
    override val eventError get() = context.getString(R.string.activity_event_error)
    override val eventStalled get() = context.getString(R.string.activity_event_stalled)
    override val eventQuestion get() = context.getString(R.string.activity_event_question)
    override val eventUnknown get() = context.getString(R.string.activity_event_unknown)
}
