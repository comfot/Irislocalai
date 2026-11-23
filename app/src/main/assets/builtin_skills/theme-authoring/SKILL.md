---
name: 主题创作
description: 生成可导入的应用主题预设 JSON
triggers: 主题,配色,强调色,液态玻璃,墨水屏,气泡样式,theme
version: 1.1.0
suite: builtin
---

## 主题创作

主题预设是 IrisMoew 的外观 JSON（schema 1），由用户在 设置 › 主题 右上角导入。

格式：

```json
{
  "app": "IrisMoew",
  "schema": 1,
  "name": "雾青玻璃",
  "style": "glass",
  "accent": "teal",
  "themeMode": "system",
  "bubbleStyle": "rounded",
  "roleChatCard": true
}
```

可用取值：

- style：standard / glass / eink
- accent：teal / blue / violet / amber / rose
- themeMode：system / light / dark
- bubbleStyle：rounded / square / outlined / minimal
- roleChatCard：true / false

规则：

1. 先确认风格意图；玻璃适合半透明亮边，墨水屏只用黑白灰，此时 accent 会被忽略。
2. 只输出上述字段，不要添加未定义字段。
3. 生成后用 write_file 保存到工作区（例如 themes/<name>.json），并提示用户在 设置 › 主题 右上角导入。
4. 不要声称已经替用户应用主题，除非用户确认已导入。
