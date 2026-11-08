package com.iris.localai.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector

data class SettingsEntry(
    val id: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
)

data class SettingsSection(
    val title: String,
    val entries: List<SettingsEntry>,
)

object SettingsCatalog {

    val sections: List<SettingsSection> = listOf(
        SettingsSection(
            title = "基础功能",
            entries = listOf(
                SettingsEntry("model", "模型", "本地模型路径与当前加载状态", Icons.Outlined.Memory),
                SettingsEntry("backend", "后端", "CPU / GPU / NPU 推理后端", Icons.Outlined.Speed),
                SettingsEntry("generation", "生成", "温度、Top-P 与最大输出长度", Icons.Outlined.AutoAwesome),
                SettingsEntry("performance", "性能与资源", "上下文长度与线程数", Icons.Outlined.Bolt),
                SettingsEntry("agent", "智能体", "工具清单与 ReAct 循环", Icons.Outlined.SmartToy),
                SettingsEntry("context", "上下文推理", "系统提示词与上下文策略", Icons.Outlined.Psychology),
            ),
        ),
        SettingsSection(
            title = "角色",
            entries = listOf(
                SettingsEntry("user", "用户", "你的称呼与个人设定", Icons.Outlined.Person),
                SettingsEntry("character-card", "角色卡", "导入、编辑与切换角色卡", Icons.Outlined.Badge),
                SettingsEntry("lorebook", "世界书", "关键词触发的设定集", Icons.AutoMirrored.Outlined.MenuBook),
                SettingsEntry("memory", "记忆", "长期记忆与检索策略", Icons.Outlined.History),
                SettingsEntry("character-model", "角色模型", "为角色绑定模型与参数", Icons.Outlined.SmartToy),
                SettingsEntry("voice", "语音", "离线识别、合成与音色", Icons.Outlined.RecordVoiceOver),
                SettingsEntry("scene", "场景", "场景模板与开场白", Icons.Outlined.TheaterComedy),
            ),
        ),
        SettingsSection(
            title = "高级功能",
            entries = listOf(
                SettingsEntry("api", "API 服务", "OpenAI 兼容的本地接口", Icons.Outlined.Api),
                SettingsEntry("mcp", "MCP", "远程与本地 MCP 服务", Icons.Outlined.Extension),
                SettingsEntry("plugin", "插件", "插件安装与权限", Icons.Outlined.Extension),
                SettingsEntry("market", "市场", "模型与扩展市场", Icons.Outlined.Storefront),
                SettingsEntry("skill", "Skill 技能", "技能的安装与触发", Icons.Outlined.AutoAwesome),
                SettingsEntry("code", "编程管理", "代码执行与工作区", Icons.Outlined.Code),
                SettingsEntry("space", "空间管理", "四个空间的会话与偏好", Icons.Outlined.Workspaces),
            ),
        ),
        SettingsSection(
            title = "界面与外观",
            entries = listOf(
                SettingsEntry("theme", "主题", "跟随系统、浅色或深色", Icons.Outlined.Palette),
                SettingsEntry("component", "组件", "气泡、字体与布局", Icons.Outlined.Widgets),
            ),
        ),
        SettingsSection(
            title = "系统管理",
            entries = listOf(
                SettingsEntry("permission", "权限", "已声明与已授予的权限", Icons.Outlined.Security),
                SettingsEntry("notification", "通知", "生成完成与后台提醒", Icons.Outlined.Notifications),
                SettingsEntry("interaction", "交互", "发送方式与手势", Icons.Outlined.Gesture),
                SettingsEntry("network", "网络", "代理、超时与下载源", Icons.Outlined.Wifi),
                SettingsEntry("integration", "接入与自动化", "外部应用与自动化触发", Icons.Outlined.Link),
                SettingsEntry("security", "安全", "本地数据与 API 鉴权", Icons.Outlined.Shield),
                SettingsEntry("about", "关于", "版本、许可与技术栈", Icons.Outlined.Info),
                SettingsEntry("developer", "开发者选项", "日志、诊断与实验开关", Icons.Outlined.Build),
            ),
        ),
        SettingsSection(
            title = "数据管理",
            entries = listOf(
                SettingsEntry("backup", "备份", "导出设置与对话", Icons.Outlined.Backup),
                SettingsEntry("restore", "恢复", "从备份或默认值恢复", Icons.Outlined.Restore),
            ),
        ),
    )

    fun find(id: String?): SettingsEntry? = sections
        .asSequence()
        .flatMap { it.entries.asSequence() }
        .firstOrNull { it.id == id }
}
