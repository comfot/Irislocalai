# 插件清单 manifest

支持 `manifest.json` 或 `manifest.hjson`（注释、无引号键、单/双/三引号、可省略逗号）。

最小示例：

```json
{
  "schema_version": 1,
  "id": "my.plugin",
  "version": "1.0.0",
  "api_version": "1",
  "main": "main.js",
  "display_name": { "zh": "我的插件", "en": "My Plugin" },
  "description": { "zh": "说明" },
  "author": ["you"],
  "enabled_by_default": true
}
```

字段：

- `schema_version`：只支持 1。
- `id`：必填，唯一；允许字母、数字、点、下划线、中划线。
- `version`：建议语义化版本；版本变化会触发内置插件更新。
- `main`：主脚本；可省略，但省略后不能注册入口。
- `subpackages`：`[{ "id": "...", "entry": "..." }]`，工具函数从子包导出。
- `tools`：清单级工具声明；只有在**恰好一个子包**时作为回退。多个子包时工具必须写在各自子包 METADATA 里。
- `resources`：`[{ "key": "...", "path": "..." }]`，供 `ToolPkg.readResource`。
- `wasm_modules`：`[{ "id": "...", "path": "..." }]`。
- `requires`：依赖声明。
- `enabled_by_default`：默认 true。

工具声明（子包脚本顶部）：

```js
/*
METADATA
{
  "tools": [
    {
      "name": "hello",
      "description": "示例工具",
      "parameters": [
        { "name": "name", "type": "string", "required": false }
      ]
    }
  ]
}
*/
exports.hello = async function (args) { return "hi " + (args.name || ""); };
```

重要：`ToolPkg` 没有 `registerTool`。工具靠 `METADATA`（或单子包时的清单 `tools`）+ 同名 `exports` 自动登记，最终名是 `plugin_<id>_<tool>`。完整说明见 `references/tools.md`。

限制：清单 ≤ 4 MB，单脚本 ≤ 4 MB，ZIP ≤ 256 MB，解压后 ≤ 512 MB，条目 ≤ 5000。
