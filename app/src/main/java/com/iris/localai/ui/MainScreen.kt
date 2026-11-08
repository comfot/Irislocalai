package com.iris.localai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.iris.localai.ui.chat.ChatScreen
import com.iris.localai.ui.settings.SettingsScreen

/** 模板只有两个一级页面：聊天与设置。设置内部的二级导航由 SettingsScreen 自己管理。 */
@Composable
fun MainScreen() {
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
    } else {
        ChatScreen(onOpenSettings = { showSettings = true })
    }
}
