# 模板

`create_project` 提供四种模板：

## tool（纯工具）

```
manifest.json
main.js
tools.js      // METADATA 声明工具，exports.<name> 实现
```

`tools.js` 必须是这种结构：

```js
/*
METADATA
{
  "tools": [
    { "name": "hello", "description": "示例工具", "parameters": [] }
  ]
}
*/
exports.hello = async function (args) { return "hello"; };
```

没有 `ToolPkg.registerTool`；工具靠 `METADATA` + 同名导出登记，最终名 `plugin_<id>_hello`。如果只有一个 `main.js`，也可以把 `METADATA` 和 `exports.hello` 都写在 `main.js` 里。

## ui（面板 + 入口）

```
manifest.json
main.js       // 注册 settings_entry / navigation / toolbox_ui
ui/panel.js   // screen(ctx)
```

适合独立 UI、设置页、侧栏入口。

## hook（事件 Hook）

```
manifest.json
main.js       // registerChatViewHook / registerToolLifecycleHook
```

适合在聊天、工具生命周期上做增强。

## host（宿主运行时）

```
manifest.json
main.js
empty.js
```

插件只做展示与开关，真实能力由宿主提供（参考内置 VRMA 动作生成）。

生成后先 `validate_project`，再 `install_project`；需要交付 ZIP 时用 `package_project`。
