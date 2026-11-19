# 宿主动作

在 UI 里：

```js
await ctx.host.invoke("motion.play", { action: "挥手" });
await ctx.host.invoke("speech.speak", { text: "你好" });
await ctx.host.invoke("settings.open", { route: "model" });
```

在工具代码里：

```js
const result = JSON.parse(irisHost.hostAction("memory.recall", JSON.stringify({ query: "上次的方案" })));
```

可用动作：

- `motion.play`：`action` 或 `alias`，驱动当前 3D 角色动作。
- `expression.set`：`expression` 或 `name`，设置当前角色表情。
- `speech.speak`：`text`，使用当前 TTS 引擎朗读。
- `memory.recall`：`query`、`limit`，返回命中记忆。
- `character.listModels`：列出角色模型与激活 id。
- `character.activeModel`：当前激活模型。
- `settings.open`：`route`，打开设置二级页（如 `model`、`voice`）。
- `toast.show`：`message`。
- `host.capabilities`：返回版本与动作清单。

参数错误会返回 `{ "ok": false, "error": "..." }`。未列出的动作不支持。
