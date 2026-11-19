# 插件 UI 方案

插件 UI 有 **3 种实现方式**，另有 **4 种承载位置**。承载位置不是独立技术，同一套 UI 可以放到不同位置。

## 3 种实现方式

### 1. Compose DSL（项目原生 UI，推荐）

用 `ctx.UI` 描述界面，节点由宿主 Compose 渲染。

```js
module.exports = function (ctx) {
  const [text, setText] = ctx.useState("text", "");
  return ctx.UI.Column({ fillMaxWidth: true }, [
    ctx.UI.Text({ text: "插件面板", bold: true }),
    ctx.UI.OutlinedTextField({
      label: "输入",
      value: text,
      onValueChange: function (value) { setText(value); }
    }),
    ctx.UI.Button({ text: "执行", onClick: function () { ctx.showToast(text); } })
  ]);
};
```

可用 16 个宿主组件：`HostChat`、`HostCharacter3DView`、`HostCharacterAvatar`、`HostModelPicker`、`HostModelProfilePicker`、`HostModelProviderPicker`、`HostScenePicker`、`HostSceneBackground`、`HostCharacterPicker`、`HostWorkspacePicker`、`HostToolList`、`HostMemorySearch`、`HostMemoryList`、`HostSkillPicker`、`HostLorebookPicker`、`HostStatus`。

适用：设置页、插件面板、页面内联 slot、轻量表单和列表。

### 2. WebView HTML/CSS/JS（复杂界面）

在 DSL 里放 `WebView` 节点，加载插件自己的 HTML。

```js
ctx.UI.WebView({
  fillMaxSize: true,
  html: "<html><body><h1>图表</h1></body></html>",
  controller: ctx.createWebViewController("chart")
})
```

- 支持 `controller` 接口调用宿主、`onInterceptRequest` 返回本地资源。
- HTML 内部不能直接渲染 `Host*` 组件，只能通过控制器接口调用宿主能力。
- 适用：图表、动画、复杂排版、富媒体。

### 3. XML 渲染 Hook（聊天消息内嵌）

注册 `registerXmlRenderPlugin`，接管聊天消息里的自定义 XML 标签。

```js
ToolPkg.registerXmlRenderPlugin({
  id: "card",
  function: function (payload) {
    return {
      handled: true,
      composeDsl: {
        screen: function (ctx) {
          return ctx.UI.Card({}, [
            ctx.UI.Text({ text: payload.eventPayload.xmlContent })
          ]);
        }
      }
    };
  }
});
```

适用：在 AI 回复里插入卡片、状态面板、操作按钮；也可以只返回文本。

## 4 种承载位置

| 位置 | 注册方式 | 说明 |
|---|---|---|
| 浮层面板 | `registerToolboxUiModule` | 从侧栏或插件页打开，近全屏可拖动面板 |
| 设置页全屏 | `registerSettingsEntry` | 出现在 设置 › 插件 分组 |
| 页面内联插槽 | `registerPluginSlot` | 注入 `settings.page.*`、`chat.*`、`space.*`、`market.*` 等 surface |
| 桌面悬浮窗 | `registerDesktopWidget` | 由 `PluginWidgetService` 以悬浮窗显示 |

## 选择建议

- 轻量面板、设置页、slot：Compose DSL。
- 复杂可视化/动画：WebView HTML。
- 在聊天消息里插卡片：XML 渲染 Hook。
- 需要常驻桌面：Desktop Widget。

## 限制

- 没有文件选择器、SAF、原生对话框和任意 Compose 代码。
- `Host*` 组件只在 Compose DSL / XML 的 compose 树中可用；WebView 内不能直接使用。
- 插件 JS 只能读写自身目录与配置目录；打包、安装、导出走 `plugin_dev`。
