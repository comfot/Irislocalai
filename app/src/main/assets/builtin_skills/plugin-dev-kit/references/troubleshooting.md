# 常见失败

- `清单缺少 toolpkg_id / id`：manifest 里必须有 id。
- `子包 xxx 的 entry 不存在`：检查 subpackages.entry 相对路径与大小写。
- `main 不存在`：manifest.main 必须指向工程内文件；纯 UI 插件也要有 main.js。
- `工具不存在或未启用`：先确认没有 `ToolPkg.registerTool`（它不存在）；工具必须在子包脚本 METADATA 或单子包清单 `tools` 中声明，并有同名 `exports`，且插件已启用。
- `不要照搬 Moodlet`：Moodlet 是从 OperitAI 导入的外部插件，文件结构不代表 IRIS 约定；以 references/tools.md 和 create_project 模板为准。
- `插件内嵌套调用自身工具暂不支持`：改由 main 或其它子包调用。
- `工具 xxx 需要用户确认，插件不能代为调用`：该工具权限被设为询问，改成允许或换方案。
- `插件 UI 事件不存在`：检查 handler 名称是否与 screen 中定义的函数一致。
- `UI 模块未注册可执行的 screen`：`screen` 必须是函数或导出 screen 的模块路径。
- `包超过限制`：检查 ZIP/解压/条目/脚本/清单上限。
- `导出目录不可写`：让用户在开发者选项授予「所有文件访问」，或改用应用外置目录回退路径。
- `PRoot 镜像未安装`：先到 编程管理 › 容器与镜像 安装。
