package com.yugahashimoto.andcode.di

import com.yugahashimoto.andcode.feature.activity.ActivityViewModel
import com.yugahashimoto.andcode.feature.chat.ChatViewModel
import com.yugahashimoto.andcode.feature.settings.SettingsViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

val viewModelModule =
    module {

        viewModel {
            ChatViewModel(
                draftRepo = get(),
                pullRequestStatuses = get(),
            )
        }

        viewModel {
            WorkspaceViewModel(
                registry = get(),
                catalog = get(),
                localRuntimeManager = get(),
                localRuntimeController = get(),
                settings = get(),
                workspaceHostDir = File(androidContext().filesDir, "runtime/workspace"),
                incompleteConnectionMessage = androidContext().getString(com.yugahashimoto.andcode.R.string.connection_info_incomplete),
            )
        }

        viewModel {
            SettingsViewModel(
                catalog = get(),
                preferences = get(),
                credentials = get(),
                settings = get(),
                registry = get(),
                voskModels = get(),
                providerDisconnectRejectedMessage =
                    androidContext().getString(com.yugahashimoto.andcode.R.string.provider_disconnect_rejected),
            )
        }

        viewModel {
            ActivityViewModel(
                catalog = get(),
                activity = get(),
            )
        }
    }
