package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.WorkspaceRef

/** Targets that can actually be named as an assistant Agent. Remote OpenCode servers have no agent identity. */
internal fun assistantTargets(targets: List<RuntimeTarget>): List<RuntimeTarget> =
    targets
        .filter { it.agent != null && it.state.value !is RuntimeState.Unavailable }
        .distinctBy { it.agent }

/** Models the selected agent can use, excluding providers that are not authenticated. */
internal fun assistantProviderOptions(catalog: ProviderCatalog): List<OpenCodeProvider> = catalog.all.filter { it.id in catalog.connected }

/** Prefer folders belonging to the assistant agent, with the chat catalogue as a safe fallback. */
internal fun assistantWorkspaceOptions(
    targetWorkspaces: List<WorkspaceRef>,
    fallbackWorkspaces: List<WorkspaceRef>,
): List<WorkspaceRef> = (targetWorkspaces.ifEmpty { fallbackWorkspaces }).distinctBy { it.path }
