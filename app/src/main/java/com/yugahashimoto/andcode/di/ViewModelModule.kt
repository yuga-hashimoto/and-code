package com.yugahashimoto.andcode.di

import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.feature.activity.ActivityViewModel
import com.yugahashimoto.andcode.feature.chat.ChatViewModel
import com.yugahashimoto.andcode.feature.settings.SettingsViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

val viewModelModule =
    module {

        viewModel {
            val app = androidContext().applicationContext as AndCodeApplication
            ChatViewModel(
                draftRepo = get(),
                pullRequestStatuses = get(),
                // Kept in step with the hand-rolled factory in ui/AndCodeApp.kt: both construction
                // paths have to park the connection probe and stall watchdog while the app is
                // backgrounded, or whichever one the screen happens to use decides whether those
                // 30-second loops keep polling out of sight.
                awaitForeground = { app.appForeground.foreground.first { visible -> visible } },
                videoNotSupportedMessage = androidContext().getString(com.yugahashimoto.andcode.R.string.error_video_not_supported),
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
