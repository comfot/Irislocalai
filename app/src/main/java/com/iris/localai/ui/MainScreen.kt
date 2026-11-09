package com.iris.localai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.data.Space
import com.iris.localai.pet.PetDestination
import com.iris.localai.pet.PetNavigator
import com.iris.localai.ui.chat.ChatScreen
import com.iris.localai.ui.settings.SettingsScreen

/** 模板只有两个一级页面：聊天与设置。设置内部的二级导航由 SettingsScreen 自己管理。 */
@Composable
fun MainScreen() {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsRequestId by rememberSaveable { mutableLongStateOf(0L) }
    val navEvent by PetNavigator.events.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }

    LaunchedEffect(navEvent?.seq) {
        val event = navEvent ?: return@LaunchedEffect
        when (event.destination) {
            PetDestination.SETTINGS -> {
                settingsEntry = null
                settingsRequestId = event.seq
                showSettings = true
            }
            PetDestination.PET_SETTINGS -> {
                settingsEntry = "pet"
                settingsRequestId = event.seq
                showSettings = true
            }
            PetDestination.LIFE_CHAT -> {
                showSettings = false
                graph.settings.update { it.copy(space = Space.LIFE) }
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            onBack = {
                showSettings = false
                settingsEntry = null
            },
            initialEntryId = settingsEntry,
            navigationRequestId = settingsRequestId,
        )
    } else {
        ChatScreen(
            onOpenSettings = {
                settingsEntry = null
                showSettings = true
            },
        )
    }
}
