---
name: 插件开发套件
description: 创建、校验、打包并安装 IRIS 插件（Operit ToolPkg 兼容）
triggers: 插件开发,写插件,打包插件,插件包,ToolPkg,插件入口,插件UI
version: 1.0.4
suite: builtin
---

## 插件开发套件

打开方式：侧栏插件区的「插件开发套件」，或 设置 › 插件 › 插件开发套件。开发者选项只负责「插件开发模式」开关、ZIP 导出目录与源码地址，不直接打开面板。

## 工具注册（先看这段）

`ToolPkg` **没有** `registerTool`。工具声明和实现是两件事：

1. 在子包脚本顶部写 `METADATA`，声明 `tools` 的名称、描述和参数；只有恰好一个子包时，也可以用 manifest 的 `tools` 回退。
2. 在同一模块用**同名**函数导出：`exports.hello = async function (args) { ... }`。
3. 宿主自动登记到 ToolRegistry，最终名是 `plugin_<插件id>_<工具名>`。

如果 manifest 没有 `subpackages`，`main` 会被当作唯一子包，`METADATA` 和 `exports` 可以直接写在 `main.js`。

详细链路、参数类型和完整示例见 `references/tools.md`。不要根据 OperitAI 导入的 Moodlet 结构推断 IRIS 的注册方式。

按下面的流程工作，不要跳过校验和安装验证：

1. 先查宿主能力：调用 `plugin_dev {"op":"capabilities"}`，确认要用的注册类型、UI 组件、slot 与宿主动作都存在。
2. 选模板建工程：`plugin_dev {"op":"create_project","project":"my.plugin","template":"tool|ui|hook|host"}`。
3. 写清单与代码：`write_file` 只写工程目录；清单字段见 `file=references/manifest.md`。
4. 校验：`validate_project`，逐条修完 errors；warnings 可接受但要确认。
5. 安装并测试：`install_project`（可传 `enabled:false` 先只安装），再从侧栏或 设置 › 插件 打开面板。
6. 打包推送：`package_project` 会把 ZIP 推到设置里的本地导出文件夹，并把路径返回给用户。
7. 修改已安装插件只能先 `copy_installed` 复制成工程，再改、再装；不要直接改插件库文件。

参考文件（需要时用 use_skill 传 file 读取）：

- references/manifest.md：清单字段与校验规则
- references/tools.md：工具声明、METADATA、同名 exports 与 ToolRegistry 登记链路
- references/entry-points.md：设置入口、侧栏入口、插件插槽、Hook、AI Provider
- references/ui-dsl.md：Compose DSL 组件与事件
- references/ui-approaches.md：3 种 UI 实现方式与 4 种承载位置
- references/host-components.md：HostChat、Character3DView、ModelPicker
- references/host-actions.md：宿主动作
- references/container.md：PRoot 容器调用
- references/templates.md：四种模板的结构
- references/troubleshooting.md：常见失败

UI 方案：轻量界面用 Compose DSL；复杂图表/动画用 `WebView` HTML；聊天消息内嵌用 XML 渲染 Hook。承载位置有浮层面板、设置页、页面内联 slot、桌面悬浮窗四种，详见 references/ui-approaches.md。

新增入口：`settings.page.<id>` 覆盖所有设置二级页；`chat.attachment`、`space.*.footer`、`space.composite.overlay`、`market.models`、`market.character-models` 也已可用。宿主组件共 16 个：HostChat、HostCharacter3DView、HostModelPicker、HostModelProfilePicker、HostModelProviderPicker、HostScenePicker、HostSceneBackground、HostCharacterPicker、HostCharacterAvatar、HostWorkspacePicker、HostToolList、HostMemorySearch、HostMemoryList、HostSkillPicker、HostLorebookPicker、HostStatus，细节见 references/host-components.md。

规则：

- 插件代码运行在 WebView 沙箱，默认只能读写自身目录与配置目录；打包、安装、导出必须走 `plugin_dev`。
- 不要声称插件已生效，除非 `install_project` 返回 ok 且用户已看到入口。
- 不要生成访问密钥、绕过权限或修改宿主源码的代码。
- 不要照搬 Moodlet 等 OperitAI 插件的结构；以本技能、references/tools.md 和 create_project 模板为准。
