# 工具注册与声明

## 先记住结论

`ToolPkg` **没有** `registerTool`。工具不是通过某个注册函数登记的，而是：

1. 在子包脚本顶部写 `METADATA` 声明工具名和参数；
2. 在同一个模块里用同名函数导出实现；
3. 宿主读取清单和 `METADATA` 后，自动登记到 `ToolRegistry`。

工具最终名是 `plugin_<插件id>_<工具名>`（特殊字符会替换成下划线）。

## 两种声明位置

### 1. 子包脚本的 METADATA（推荐）

```js
/*
METADATA
{
  "tools": [
    {
      "name": "hello",
      "description": "返回一条示例消息",
      "parameters": [
        { "name": "name", "description": "称呼", "type": "string", "required": false }
      ]
    }
  ]
}
*/

exports.hello = async function (args) {
  return "你好，" + (args.name || "世界");
};
```

### 2. manifest 的 `tools` 数组（回退）

只有**恰好一个子包**时，宿主才会用清单里的 `tools` 作为回退。多个子包时，工具必须写在各自子包的 `METADATA` 里。

```json
{
  "main": "main.js",
  "tools": [
    {
      "name": "hello",
      "description": "示例工具",
      "parameters": [{ "name": "name", "type": "string", "required": false }]
    }
  ]
}
```

注意：如果 manifest 没有 `subpackages`，`main` 会被当作唯一的子包（id 为 `main`）。所以只有一个 `main.js` 时，也可以把 `METADATA` 和 `exports.hello` 都写在 `main.js` 里。

## 实现函数

- 工具名必须和导出名完全一致。
- 支持 `exports.hello = async function (args) {}`，也支持 `module.exports = { hello: async function (args) {} }`。
- `args` 是 JSON 对象；返回字符串或可序列化对象。
- 需要 UI/Hook 时，`main.js` 继续用 `ToolPkg.registerToolboxUiModule` 等注册；工具函数放在子包里。

## 参数类型

`type` 支持：`string`、`number`、`integer`、`boolean`、`array`、`object`。

- `required` 默认 `true`；缺必填参数会直接报错。
- `array` / `object` 参数可以传 JSON 字符串，宿主会尝试解析。
- 工具描述要写清楚用途，Agent 靠它选择工具。

## 宿主登记链路

```text
manifest + 子包 METADATA
  → PluginStore.load 解析出 InstalledPlugin.tools
  → PluginStore.enabledBindings
  → PluginToolInstaller.sync
  → ToolRegistry.register(PluginTool)
  → Agent 调用 plugin_<id>_<tool>
  → PluginTool.execute
  → PluginRuntime.callTool(subpackage, toolName, args)
  → module[<toolName>](args)
```

## 验证是否登记成功

- `plugin_dev {"op":"status"}` 返回的插件项里 `tools` 数量增加。
- 设置 › 插件 › 查看工具，能看到工具名和运行时名。
- Agent 工具清单里出现 `plugin_<id>_<tool>`。

## 不要照搬 Moodlet 等 OperitAI 插件

Moodlet 是从 OperitAI 导入的外部插件，它的文件结构和注册习惯不一定符合 IRIS 的约定。判断 IRIS 插件怎么写，只以：

- 本文档；
- `references/manifest.md`；
- `plugin_dev {"op":"create_project","template":"tool"}` 生成的模板；

为准。不要因为 Moodlet 没有 `METADATA` 或使用了别的注册方式，就认为 `ToolPkg.registerTool` 存在或工具无法声明。
