# 宿主 API 参考

IRIS LocalAI 为插件提供三类扩展：

1. 注册入口：settings_entry、navigation、plugin_slot、toolbox_ui、Hook、AI Provider。
2. 宿主组件：HostChat、HostCharacter3DView、HostModelPicker，以及基础 Compose DSL。
3. 宿主动作：motion.play、expression.set、speech.speak、memory.recall、character.*、settings.open、toast.show。

宿主能力清单请以 `plugin_dev {"op":"capabilities"}` 的返回为准；本目录文档随宿主版本更新。

源码参考：开发者选项可配置 GitHub 仓库与分支，`plugin_dev {"op":"host_source","path":"app/src/main/java/..."}` 按需读取并缓存。

限制：

- 插件 JS 只能读写自身目录与配置目录。
- 打包、安装、导出必须通过 `plugin_dev`。
- 已安装插件只读，修改前先 `copy_installed`。
- 外部通道与 MCP 不暴露 `plugin_dev`。
