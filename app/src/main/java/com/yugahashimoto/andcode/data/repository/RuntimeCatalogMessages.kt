package com.yugahashimoto.andcode.data.repository

import android.content.Context
import com.yugahashimoto.andcode.R

/** User-visible labels used when a runtime catalogue refresh reports partial failures. */
interface RuntimeCatalogMessages {
    fun sessions(error: String): String

    fun providers(error: String): String

    fun agents(error: String): String

    fun workspaces(error: String): String

    val connectionFailed: String

    companion object Default : RuntimeCatalogMessages {
        override fun sessions(error: String) = "Sessions: $error"

        override fun providers(error: String) = "AI services: $error"

        override fun agents(error: String) = "Agents: $error"

        override fun workspaces(error: String) = "Workspaces: $error"

        override val connectionFailed = "Could not connect to OpenCode"
    }
}

class AndroidRuntimeCatalogMessages(private val context: Context) : RuntimeCatalogMessages {
    override fun sessions(error: String): String = context.getString(R.string.runtime_catalog_sessions, error)

    override fun providers(error: String): String = context.getString(R.string.runtime_catalog_providers, error)

    override fun agents(error: String): String = context.getString(R.string.runtime_catalog_agents, error)

    override fun workspaces(error: String): String = context.getString(R.string.runtime_catalog_workspaces, error)

    override val connectionFailed get() = context.getString(R.string.runtime_catalog_connection_failed)
}
