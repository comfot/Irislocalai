# 插件入口与注册类型

工具不通过 `registerTool` 注册；见 `references/tools.md`。本文件只讲入口、Hook 与 AI Provider。

在 `main.js` 里直接调用（无需 `registerToolPkg`）：

```js
ToolPkg.registerSettingsEntry({
  id: "my_settings",
  title: "我的插件",
  surface: "advanced",     // settings 分组：advanced / system / data / character / basic / appearance
  order: 50,
  screen: "ui/settings.js" // 也可以是 function
});

ToolPkg.registerNavigationEntry({
  id: "my_nav",
  title: "我的插件",
  surface: "main_sidebar_plugins",
  route: "my_nav",
  screen: "ui/panel.js"
});

ToolPkg.registerToolboxUiModule({
  id: "panel",
  title: "我的插件",
  screen: "ui/panel.js"
});

ToolPkg.registerPluginSlot({
  id: "model_slot",
  title: "模型扩展",
  surface: "settings.model",
  order: 10,
  screen: "ui/slot.js"
});
```

支持的 surface（第一版）：

```text
settings.model / settings.voice / settings.character-card / settings.memory
settings.page.<id>（任意设置二级页顶部，id 如 model / voice / developer-options）
chat.attachment / chat.input / chat.message-menu / chat.empty
space.work / space.life / space.operate / space.composite
space.work.footer / space.life.footer / space.operate.footer / space.composite.overlay
sidebar.bottom / market.plugins / market.models / market.character-models / character.detail
```

Hook 类型：`chat_input`、`chat_view`、`chat_message`、`chat_message_menu_item`、`chat_runtime`、`tool_lifecycle`、`input_menu_toggle`、`app_lifecycle`、`xml_render`、`message_processing`、`prompt_input`、`prompt_history`、`system_prompt_compose`、`tool_prompt_compose`、`prompt_finalize`、`summary_generate`。

AI Provider：`ToolPkg.registerAiProvider({ id, listModels, sendMessage, testConnection })`。

注意事项：

- 入口只在插件启用时出现；插件停用或被删除后自动移除。
- 设置入口和侧栏入口都用 `screen` 指向 UI 模块；事件通过 `ctx` 的 handler 回传。
- 插件开发套件自身入口受「插件开发模式」开关控制。
