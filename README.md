# IRIS LocalAI（Android 模板）

以 [IRIDIS](https://github.com/WWRJ3321/IRIDIS) 的工程结构为参照改造的 Android 模板：
单模块 `:app`、Kotlin + Jetpack Compose、手动依赖装配，内置 **推理引擎抽象**、**Agent 工具循环**
与 **OpenAI 兼容的本地 HTTP/SSE 服务**。GGUF 本地模型通过预编译 llama.cpp 运行库推理；MNN/LiteRT 仍为后续接入项。

## 已实现的能力

- 聊天主界面：对话气泡、流式输出、工具调用卡片、停止生成、API 快捷开关、会话标题自动命名
- 侧边栏：四空间切换、新建/选择/删除会话、设置入口
- 四空间：工作 / 生活 / 操作 / 综合，会话按空间隔离
- 综合空间：可切换的六场所 3D 场景（工作室 / 园区 / 图书馆 / 工地 / 值班室 / 前台），支持拖动旋转、双指缩放、点击楼栋进入与动态标签
- 设置体系：6 大分组共 32 个入口，含模型、后端、生成、性能、智能体、上下文、API、主题、空间、权限、关于、备份/恢复等页面
- 模型配置：多档案（新建/重命名/删除/测试连接）、本地/云端提供商、五类角色模型、远程 OpenAI 兼容流式引擎；提供商「支持识图」开关开启后，图片附件会按视觉角色模型以 `image_url` 多模态内容发送
- 本地模型下载：设置 › 市场 搜索 Hugging Face、GGUF 断点续传、官方源失败自动回退 hf-mirror；HF 无结果时自动使用 ModelScope 备用检索，并内置 IRIDIS 推荐 GGUF 目录
- 插件：导入/转换 Operit ToolPkg（manifest.json/hjson + 子包 METADATA），插件工具注册进智能体；WebView 沙箱执行脚本、限制文件与网络访问
- 角色模型广场：参照 MikuMikuAR 聚合模之屋、Bowlroll、BOOTH、VRoid Hub 等站点与创作者名录，支持站点搜索与从链接导入 VRM/GLB/Live2D/MMD
- 用户资料：头像、显示名称、叙事描述、视觉提示标签，并注入角色扮演提示词
- 角色卡：PNG/JSON 的 V1/V2/V3 与春水别名导入，创建/编辑/收藏/删除/导出，列表与缩略图布局
- 世界书：酒馆 world info 导入、条目编辑、关键词/常驻/概率/位置与全局扫描/预算设置
- 记忆：日记、记忆库、知识库三库，支持本地检索并注入对话
- 角色模型：Live2D ZIP、VRM、GLB/GLTF、MMD 导入与管理，抽取缩略图；VRM/GLB、Live2D 与 MMD 在生活空间实际渲染，角色卡可绑定专属模型
- 语音：TTS/STT 均支持系统、本地模型、云端三种类型；IndexTTS2 等模型提供 Hugging Face 下载入口
- 场景：生活空间背景图/视频、名称/介绍、3D 模型底座样式与颜色
- API 服务：OpenAI 兼容接口（models / chat/completions / completions / health），可开放给其他应用；支持 API Key、局域网与访问地址
- MCP：客户端接入其它 MCP 服务并把工具注入 Agent；服务端把内置工具暴露给本机 AI 客户端
- SKILL：SKILL.md 导入/创建/删除/下载，自动触发与提示词注入，并注册 `use_skill` 工具按需读取
- 编程管理：应用 Shell 与 PRoot 容器运行命令，运行时/镜像、命令通道与工具授权；应用 Shell、PRoot 与工作区文件工具仅在工作/综合空间生效
- 操作空间：权限状态（无障碍 / 悬浮窗 / 麦克风 / Shizuku）、前台与后台执行方式、交互方式、常用指令与执行日志
- `EngineManager`：加载/卸载、状态广播（Idle / Loading / Ready / Failed）、生成与取消
- `AgentLoop`：ReAct 风格循环，解析 `<tool_call>{...}</tool_call>`，并兼容模型原生的 DSML 工具标签；最多 4 步工具调用
- 工具层：`Tool` + `ToolRegistry`，内置 `current_time`、`device_info`、`random_pick`、`use_skill`、`memory`、`read_file`、`write_file`、`edit_file`、`list_files`、`search_files`、`workspace`、`fetch_url` 与 `shell`；旧安装升级时自动补开新工具
- 会话持久化：`ConversationStore` 以 JSON 落盘到 `filesDir/conversations.json`，重启后恢复
- 语音朗读：系统 TTS 支持按语速自动朗读助手回复
- 本地 API：`GET /health`、`GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/completions`（`stream=true` 走 SSE）
- 设置存储：`SettingsRepository`（SharedPreferences + StateFlow），含 backend / 采样 / 端口 / 空间 / 主题 / 用户名

## 目录结构

```text
app/src/main/assets/
├── studio.html              # 综合空间页面与标签样式
├── studio.js                # 六场所 3D 场景与交互
├── three.min.js             # 综合空间离线 three.js
├── character.html           # 生活空间 VRM/GLB 页面
├── character.js             # GLB/VRM 加载、相机与交互
├── live2d.html              # Live2D 渲染页面
├── live2d.js                # Live2D 加载、缩放与拖拽
├── mmd.html                 # MMD 渲染页面
├── mmd.js                   # PMX/PMD 加载与舞台适配
└── model/
    ├── three.min.js         # 角色模型离线 three.js r170
    ├── GLTFLoader.js        # glTF/GLB/VRM 加载器
    ├── live2dcubismcore.min.js # Live2D Cubism Core
    ├── pixi.min.js          # Pixi 渲染器
    ├── pixi-live2d-display-cubism4.min.js # Live2D Pixi 插件
    ├── babylon/             # Babylon.js + babylon-mmd UMD 与 WASM 物理
    ├── mmd-idle.vmd         # 内置 MMD 待机动作
    ├── three-mmd.min.js     # 旧 MMD 渲染器（保留兼容）
    ├── MMDLoader.js
    ├── MMDToonShader.js
    ├── mmd-parser.min.js
    ├── LICENSE-three.txt
    └── LICENSE-live2d.txt

app/src/main/java/com/iris/localai/
├── IrisApp.kt                 # Application，持有依赖图
├── AppGraph.kt                # 手动装配（替换引擎就在这里）
├── MainActivity.kt            # 唯一 Activity
├── data/
│   ├── AppSettings.kt
│   ├── SettingsRepository.kt
│   ├── Space.kt               # 四空间与主题模式
│   ├── Profiles.kt            # 工作区 / 技能 / MCP / 角色卡
│   ├── ModelConfig.kt         # 档案、提供商、五类角色模型
│   ├── ModelProfileStore.kt   # 多档案 JSON 持久化
│   ├── ModelConnection.kt     # 连接测试
│   ├── HuggingFaceClient.kt   # HF 搜索/下载（官方源 + hf-mirror）
│   └── ModelDownloadManager.kt
├── engine/
│   ├── Backend.kt
│   ├── InferenceEngine.kt     # 引擎契约与消息/采样/事件类型
│   ├── EngineManager.kt       # 唯一引擎入口 + EngineState
│   ├── OpenAiCompatibleEngine.kt # 云端 SSE 流式推理
│   ├── llama/                 # 预编译 libiris_llama.so + GGUF 引擎
│   │   ├── LlamaNative.kt     # JNI 声明
│   │   └── LlamaEngine.kt     # llama.cpp 加载/流式生成/取消
│   └── StubEngine.kt          # 占位引擎（无本地模型时）
├── agent/
│   ├── Tool.kt
│   ├── ToolRegistry.kt
│   ├── AgentEvent.kt
│   ├── AgentLoop.kt
│   ├── Conversation.kt        # ChatMessage / Conversation
│   ├── ConversationStore.kt   # 按空间管理会话（JSON 持久化）
│   └── tools/                 # current_time / device_info / random_pick / use_skill / workspace
├── voice/
│   └── TtsController.kt       # 系统 TTS 自动朗读
├── workspace/
│   ├── WorkspaceStore.kt      # 工作区条目与 SAF 权限
│   └── WorkspaceFs.kt         # 应用内 / SAF 文件读写
├── server/
│   ├── HttpServer.kt          # 零依赖 HTTP/1.1 + SSE
│   ├── ApiRoutes.kt           # OpenAI 兼容路由
│   └── ApiService.kt          # 服务生命周期（默认 127.0.0.1:8686）
└── ui/
    ├── MainScreen.kt          # 聊天 / 设置一级切换
    ├── chat/
    │   ├── ChatScreen.kt      # 抽屉 + 聊天主界面
    │   ├── ChatViewModel.kt   # 多会话、空间联动、生成与朗读
    │   ├── CompositeSpaceScreen.kt # 六场所 3D 综合空间
    │   ├── Studio3DView.kt    # WebView 桥接与状态注入
    │   └── Sidebar.kt         # 工作区、角色卡、会话列表
    ├── settings/
    │   ├── SettingsCatalog.kt # 6 大分组 32 个入口
    │   ├── SettingsScreen.kt  # 设置首页 + 二级导航
    │   ├── SettingsComponents.kt
    │   ├── SettingsPages.kt   # 通用配置页与占位页
    │   ├── ModelSettingsPage.kt # 模型档案 / 提供商 / 角色模型
    │   ├── MarketPage.kt      # Hugging Face 搜索与下载
    │   └── MarketViewModel.kt
    └── theme/                 # IRIDIS 默认配色（HyperOS 蓝 / 黑）
```

## 界面与空间

- `MainScreen` 只负责聊天与设置两个一级页面，设置内部用 `entryId` 维护二级导航并接管返回键
- 顶栏右侧只有一个图标按钮用于切换空间（下拉选择），不再占用文本；设置入口保留在侧栏底部
- 侧栏 `Sidebar` 包含当前空间会话、可折叠的工作区文件夹列表与角色卡横排；点击角色卡会切到生活空间

| 空间 | 主界面 | 输入栏 |
|---|---|---|
| 工作空间 | AI 对话、Markdown 回答、思考过程、工具调用卡片 | 工作区 / 模型 / 权限 / 工具模式 / 操作配置 / 附件 / 语音 / 发送 |
| 生活空间 | 约 2/3 屏高的角色舞台、场景背景与底座、VRM/GLB/Live2D 模型、陪伴对话 | 文本 / 语音 / 视频通话 / 表情 |
| 操作空间 | 权限中心、执行方式、执行日志 | 文本 / 语音 / 快捷指令 |
| 综合空间 | 六场所全屏 3D 场景、场所标签、最近会话 | 无输入栏，通过场所标签或园区楼栋切换 |

- 工作区是真实文件夹：通过系统文件管理器选择（SAF 持久化权限）或在应用容器内新建；侧栏可折叠展开，工作空间工具条同步切换
- 工作区按会话绑定：切换会话会自动恢复该会话的工作区，新建会话绑定当前工作区；会话记录存放在 `conversations.json`
- 工作空间输入栏支持附件：可浏览当前工作区或从设备选择文本/图片；文本内容注入提示词，图片在模型开启「支持识图」后按视觉质量缩放并以多模态内容发送（单文本 8000 字符、最多 5 个附件）
- 模型档案、权限模式、技能与 MCP 勾选持久化在 AppSettings；工作区由独立的 WorkspaceStore 管理
- 角色卡与 2D/3D 选择持久化，并同步到侧栏、生活空间与设置页
- 会话由 `ConversationStore` 管理，支持新建、选择、删除（带确认）、按首条消息自动命名，并以 JSON 持久化

## 工作区与 3D 场景

- **工作区文件夹**：系统文件管理器选择任意文件夹，或创建 `filesDir/workspaces/<name>` 容器工作区；侧栏“工作区”可折叠，设置页可新增/重命名/移除条目（移除不删除文件夹内容）；每个会话记住自己的工作区
- **六场所 3D 场景**：综合空间使用本地 `assets/studio.html` + `assets/studio.js` + `three.js` 离线渲染，参照 agent-workspace 的工作室布局重建
  - 工作室：工位、会议桌、沙发、书架、白板与玻璃工具沙盒
  - 园区：工作室、图书馆、工地、值班室、前台五栋建筑与草坪、树木、路灯；点击楼栋或标签进入对应场所
  - 图书馆：书架、阅读桌、借阅台与书车；标签显示知识库、记忆与日记数量
  - 工地：围挡、塔吊、脚手架与按工作区街区摆放的文件楼
  - 值班室：监控墙、控制台、服务器机柜、地图屏与时钟；显示 API / MCP / 引擎状态
  - 前台：长柜台、徽章板、包裹架与等候长椅；显示本机服务端口
- 场景标签由 HTML 叠层投影到 3D 坐标，会按相机位置避让；角色强调色与明暗主题实时传入
- 支持拖动旋转、双指缩放、重置视角、暂停动画与闲置自动旋转；3D 渲染完全离线，WebView 仅加载本地 assets
- 页面脚本在运行时内联进 HTML，规避 file:// 页面加载子资源时的同源限制；WebGL 不可用或脚本异常时会显示可见的错误提示
- 场所切换、园区点击和状态数据通过 Compose ↔ WebView 桥接同步，不重建 Activity

## 用户、角色卡与世界书

- **用户资料**：头像（复制到应用私有目录）、显示名称、叙事描述、视觉提示标签；叙事与标签会随对话注入提示词
- **角色卡导入**：PNG（读取 tEXt/zTXt/iTXt 的 chara/ccv3，兼容 ccv2/character/card）与 JSON（SillyTavern V1/V2/V3），同时兼容春水/Pygmalion 的 `char_name`、`char_persona`、`world_scenario`、`char_greeting`、`example_dialogue` 等别名和 `character`/`card` 包装
- **卡片管理**：创建、编辑、收藏、删除、导出 JSON/PNG；列表/缩略图布局；导入的 PNG 卡保留原图作为头像；内嵌 `character_book` 会自动导入为世界书
- **存储**：`filesDir/characters/<id>/card.json` + `avatar.*`，首次启动种入小满/岚/阿澈三张内置卡
- **世界书**：导入 SillyTavern world info JSON（entries 对象或数组），条目支持主/二级关键词、二级逻辑、整词/正则/大小写、常驻、概率、顺序、插入位置/深度、粘滞/冷却；全局支持扫描深度、Token 预算、最少激活、最大递归与常驻条目开关
- **运行时**：对话时按关键词解析已启用的世界书，命中内容与角色设定一起注入系统提示；角色设定仅在生活空间生效

## 记忆与角色模型

### 记忆三库

- **日记**：按日期记录标题、心情、标签与正文，支持新增/编辑/删除；最近三篇会进入提示词
- **记忆库**：事实/事件两类，带标签、重要度与启用开关；按重要度和时间排序后注入
- **知识库**：导入纯文本（上限 4 MB）或手写笔记，支持搜索、启用/停用；对话时按关键词检索前两条注入
- 注入受「上下文推理」页的记忆总开关、记忆注入对话、知识库检索开关控制；预算约 2400 字
- 数据存于 `filesDir/memory/{diary,memories,knowledge}.json`

### 角色模型

- 支持 Live2D（完整模型文件夹压缩为 ZIP，含 `model3.json`）、VRM（0.x/1.0）、GLB/GLTF、MMD（PMX/PMD）
- 导入后存放在 `filesDir/character_models/<id>/`，自动识别类型、模型入口与文件体积
- 尽量抽取缩略图：VRM/GLB 从 GLB 元数据读取 meta 缩略图；Live2D/MMD 取模型目录内较大的贴图
- 列表支持设为当前模型、绑定到角色卡、取消当前、删除；生活空间优先使用该角色卡绑定的模型，未绑定时回退到全局当前模型
- 角色绑定存在 `iris_character_models` 的 `bindings` 中；删除模型会同步移除相关绑定
- 生活空间舞台占内容区约 2/3 高，场景背景图/视频、底座样式与颜色都裁剪在舞台内部，聊天区使用应用背景
- 生活空间已接入本地 `character.html` + `character.js` + three.js r170 + GLTFLoader：GLB/VRM 实际渲染，自动适配身高与相机，支持拖动旋转、双指缩放、闲置旋转与 GLB 内置动画
- 模型请求通过虚拟 `https://iris.local/models/...` 由 `WebViewClient.shouldInterceptRequest` 从 `filesDir/character_models/` 流式返回，避免 file:// 下 fetch 被限制
- 生活空间已接入 Live2D：本地 Cubism Core + Pixi + pixi-live2d-display，支持 model3.json、贴图、物理与动作，自动适配舞台，支持拖动与双指缩放
- Live2D 表情映射：按回复文本识别开心/生气/难过/惊讶/思考，映射到模型自身的 Expression 或 Motion 分组；无表情文件时回退到动作分组
- Live2D 口型：生成中或系统 TTS 朗读时驱动 `ParamMouthOpenY`，停止后闭合；TTS 播放状态来自 `UtteranceProgressListener`
- Live2D 使用 2D 显示模式；导入/绑定 Live2D 时自动切回 2D
- MMD 使用独立 `mmd.html` + `mmd.js`，本地 Babylon.js + babylon-mmd + WASM 物理运行时，支持 PMX/PMD、VMD 动作、IK、表情与刚体物理；导入/绑定 MMD 时自动切到 3D
- MMD 动作：优先加载模型同目录的 `.vmd`，没有时使用内置 `mmd-idle.vmd` 待机动作；模型目录中的 VMD 会随模型一起保留
- MMD 性能：渲染分辨率按实际 FPS 自动降级/恢复，像素比上限 1.35；Babylon 连续渲染，WASM 物理单线程运行
- 模型位置：角色模型设置页提供左右/上下偏移（±50%），按模型持久化，生活空间与全屏共用

## 语音与场景

### 语音

- **TTS 引擎**：系统 TTS / 本地模型 / 云端
  - 系统：使用 Android TextToSpeech，可测朗读、调节语速；开启自动朗读后会朗读每轮助手回复（长文本自动分段）
  - 本地：IndexTTS-2（约 5.9 GB）与 Kokoro 多语言，从 Hugging Face 多文件下载并安装；本地推理待接入
  - 云端：OpenAI / 火山引擎 / 自定义，Endpoint、API Key、模型、音色参数持久化
- **STT 引擎**：系统识别 / 本地模型 / 云端
  - 系统：调用系统 SpeechRecognizer，支持中/英/日，会请求录音权限
  - 本地：SenseVoice Small 与 Paraformer 中文，Hugging Face 下载与安装管理；本地 ASR 待接入
  - 云端：OpenAI / 火山引擎 / 自定义参数持久化
- 语音模型下载复用 Hugging Face 客户端（官方源 → hf-mirror 回退），支持进度、取消、删除、断点续传

### 场景

- 场景名称、介绍、背景图/视频、底座样式（无/圆形舞台/网格平台/光晕底座）与底座颜色
- 背景从系统文件选择器导入，存于 `filesDir/scenes/`；图片用 Compose 绘制，视频用系统 VideoView 静音循环
- 可在设置中启用某个场景；生活空间会使用当前场景背景，并在角色舞台显示场景名与 3D 底座
- 底座与场景参考 [xiaokeai](https://github.com/liupig/xiaokeai) 的 3D 舞台概念；VRM/GLB/Live2D/MMD 均已接入实际渲染

## SKILL 与编程管理

### SKILL 技能

- 格式：SKILL.md，YAML frontmatter（name、description、triggers、alwaysOn、version、suite）+ Markdown 指令正文
- 导入支持单个 SKILL.md 或 ZIP 内的 .md；可创建、编辑、删除；URL 下载（内置 Agent Workspace 官方技能推荐源）
- 激活配置：自动触发（按触发词匹配）、注入对话/智能体、常驻注入、注入字符预算
- 对话时会合并命中技能的指令并注入系统提示；Agent 页与工作空间操作配置共用技能库
- 注册 `use_skill` 工具：模型可按名称读取已启用技能的 SKILL.md，作为操作参考按需加载；不带参数时列出可用技能
- 存储：`filesDir/skills/<id>/SKILL.md` + `meta.json`

### 插件系统

- 兼容 Operit ToolPkg：读取 `manifest.json` / `manifest.hjson`（`toolpkg_id`、`display_name`、`subpackages`、`resources`、`requires`），从子包脚本顶部的 `/* METADATA ... */` 解析工具名、描述、参数与必填标记
- 也接受 IRIS 插件清单（`id` / `name` / `entry` / `tools`），ZIP 根目录或一级子目录内的清单均可
- 安装来源：本地 ZIP、HTTP/HTTPS URL、Operit 市场 v2 资产（校验 SHA-256）、插件市场 GitHub 仓库归档；安装后存放在 `filesDir/plugins/<id>/`，启用状态写入 `iris-plugin.json`
- 参数兼容：按 METADATA 的 `type` 转换 number/integer/boolean/array/object，`required` 缺省为 true；`advice: true` 的工具直接返回说明文本，不执行脚本
- 工具注册：启用的插件工具以 `plugin_<包名>_<工具名>` 进入 Agent 工具表，参数以文本清单写入工具描述
- 执行沙箱：每个插件保留一个 WebView 会话，加载 main 与全部子包；支持 CommonJS `require`、同包 `ToolPkg.ipc`、`ToolPkg.wasm.call`（WebAssembly 标量 ABI）与 `main` 注册收集；文件访问限制在插件/配置目录，网络仅公网 HTTP/HTTPS，单次调用 30 秒超时
- Hook：已接入聊天输入 `submit_requested`（allow/block/replace/consume）、工具 `tool_call_intercept`、系统提示词 `after_compose_system_prompt`、消息持久化、聊天运行态通知与工作空间消息长按菜单（回调结果以文本提示展示，Compose DSL 弹窗不支持）；其余 Prompt 阶段与 AI Provider 仅收集注册信息
- Java 兼容：白名单实现 `java.io.File`、`java.nio.file.Files`、`java.lang.String/StringBuilder`、`Base64`、`MessageDigest`、`ArrayList/HashMap`、`FileOutputStream/Writer` 等常用类；未知类直接报错，不暴露任意反射、`Java.implement` 或 Android SDK
- 兼容边界：Operit Compose DSL UI（`registerToolboxUiModule` / `registerUiRoute`）是 Compose 节点树，无法在 WebView 中渲染；依赖任意 Java 反射、`Java.implement`、`loadDex/loadJar` 或宿主单例的插件无法等价运行

### 编程管理

- 仅作用于工作空间与综合空间；生活/操作空间不加载 Shell 工具
- 运行时：应用 Shell 与 PRoot 容器（随包 proot 启动器 + rootfs 安装）/ 远程 SSH（待接入）
- 容器与镜像：Alpine 内置下载；Ubuntu / Debian / 自定义镜像可填写 tar.gz 下载地址或使用本地压缩包；安装/重装 rootfs 带进度与取消
- PRoot：rootfs 存放在 `filesDir/proot/<imageId>/linux`，当前工作区挂载到容器 `/workspace`；自动补 DNS/hosts/locale，命令带超时与输出上限；仅支持应用内工作区
- 命令通道：超时、输出上限、网络开关与 SSH 连接字段
- 工具授权：Shell / 文件 / 网络 / 代码分别允许、询问、拒绝；支持允许/禁止命令前缀
- Shell 工具在当前应用内工作区执行 `/system/bin/sh`；未选择应用内工作区时回退 `filesDir/workspace`，SAF 工作区会提示切换
- 工作区文件工具：`workspace` 工具支持 list/read/write/mkdir/delete/search；应用内工作区用 `File` 与 canonical 路径校验，SAF 工作区用 ContentResolver + DocumentsContract，文本上限 200 KB，删除仅限单文件或空目录
- Shell 授权与文件授权分别控制；默认未启用编程管理，授权为「询问」

### 操作空间

- 顶部显示“已就绪 / 部分就绪 / 未就绪”，并汇总当前执行方式与权限数量
- 权限清单：无障碍服务（开启/连接两态）、悬浮窗、麦克风、通知、Shizuku（安装/运行/授权三态）；操作空间与设置›权限页共享同一状态，返回应用自动刷新
- 无障碍服务 `IrisAccessibilityService` 已支持点击、滑动、返回、桌面、文本输入与截图
- Shizuku：支持授权申请、shell 命令执行、通过 Shizuku 自动开启无障碍；后台/Shizuku 模式优先走 Shizuku，失败回退无障碍
- 后台运行方式三选一：普通屏幕、Aries 风格虚拟屏、本机 freeform 小窗
  - 虚拟屏：Shizuku + HiddenApiBypass 创建隐藏 display，ImageReader 收帧截图，MotionEvent/KeyEvent 定向注入；操作空间提供 200ms 刷新的画面预览和“带回主屏”
  - 本机小窗：透明代理 Activity 使用 ActivityOptions freeform 启动，失败时回退 `--windowingMode 5` 命令或普通启动
- 操作工具：`device_open_app`、`device_tap`、`device_swipe`、`device_back`、`device_home`、`device_input_text`、`device_screenshot`，仅在操作/综合空间注入；权限不足会返回可读错误
- 执行记录来自 Agent 工具步骤，可复制；常用指令一键填入并发送

## API 与 MCP 服务

### API 服务

- OpenAI 兼容：`/v1/models`、`/v1/chat/completions`、`/v1/completions`（均支持 SSE）、`/health`，模型名来自当前模型档案
- 设置页可启停、改端口（重启生效）、开启局域网、生成/保存/复制/清除 API Key
- 默认只绑定 `127.0.0.1`；局域网必须设置 Key，否则强制回落回环地址
- 鉴权：`Authorization: Bearer <key>` 或 `x-api-key`；无 Key 时拒绝浏览器 Origin 与非回环 Host
- 展示当前访问地址与 curl 示例；开机后若已启用会自动启动

### MCP 服务

- **客户端**：添加任意 MCP Streamable HTTP 服务（URL + 自定义请求头），执行 initialize → tools/list，并把工具以 `mcp_<server>_<tool>` 注册进 Agent 工具表；支持断开、编辑、删除
- **服务端**：把内置工具（`current_time`、`device_info`、`random_pick`、`use_skill`）以 MCP 暴露：initialize / tools/list / tools/call / ping，默认 `http://127.0.0.1:8765/mcp`；`shell` 与 `workspace` 仅限本机 Agent，不对外暴露
- 服务端可改端口、设 API Key、开启局域网；开启后开机自动启动
- 不暴露已接入的 MCP 客户端工具，避免递归

## 模型配置与 API

- 配置档案：可新建、重命名、删除、切换，默认提供「默认配置」；支持测试连接（云端请求 `/models`，本地检查文件）
- 模型提供商：本地 / 云端分类。云端可选 OpenAI、Anthropic、Gemini、DeepSeek、OpenRouter、自定义，填写 API Key、Endpoint、模型名，并提供识图 / 音频 / 视频 / 联网搜索开关
- 应用全局配置与 API 秘钥池：界面开关，秘钥池轮询逻辑待接入网关
- 角色模型：语言（模型、上下文、总结、思考、参数、自定义请求头、工具调用）、视觉、创作、Embedding、动作
- 远程推理：云端 Key 非空时，聊天自动切换到 `OpenAiCompatibleEngine`，按 SSE 流式解析 `delta.content`；修改配置后下一次发送会自动重载引擎
- 本地推理：GGUF 通过预编译 `libiris_llama.so`（llama.cpp）加载，支持 CPU 与 OpenCL GPU；GPU 失败可按开关回退 CPU，FlashAttention/KV/Batch/mmap/线程数会传入 native。MNN、LiteRT 与 NPU 仍未接入
- 本地下载：设置 › 市场 › 模型市场 › LLM 本地模型。推荐区来自 IRIDIS 的 GGUF 目录；搜索优先 Hugging Face，失败或无结果时自动切换到 ModelScope（参照 Zorv AI 离线模型下载中心的检索与文件树接口）。下载使用 `.part` 断点续传，官方源超时自动回退 `hf-mirror.com`，完成后可一键「设为本地模型」并同步引擎路径
- 角色模型广场：设置 › 市场 › 模型市场 › 角色模型。参照 MikuMikuAR 的广场结构，聚合模型站点与创作者名录（远端 `creators.json` / `workshop_sites.json`，失败回退内置站点），可打开站点搜索或从链接下载 ZIP/VRM/GLB/PMD/PMX 后直接导入角色模型库
- 插件市场：设置 › 市场 › 插件市场。可导入本地 ZIP、从 URL 安装，或浏览 Operit 官方市场 v2 索引（static.operit.app，按下载/喜欢/更新排序）并校验 SHA-256 后安装；GitHub 搜索 ToolPkg 仓库作为补充发现路径。已安装插件可启用/停用、删除

注意：本地 GGUF 已接入 llama.cpp；MNN/LiteRT/NPU 仍未接入。API Key 以明文保存在 SharedPreferences，模板未做加密。

## 基础功能设置（对照 IRIDIS）

- **模型**：多配置档案、本地/云端提供商、五类角色模型、加载选项（自动加载、视觉质量、下载源、Flash Attention、KV 缓存类型、批大小、mmap、预热）
- **后端**：GGUF/llama.cpp 已接入（CPU/GPU）；MNN/LiteRT 与 NPU 未接入；GPU 失败可回退 CPU
- **生成**：系统提示词、深度思考与预算、最大输出、温度/Top-P、Top-K/Min-P/重复惩罚/重复窗口/存在与频率惩罚/固定种子、恢复默认
- **性能与资源**：省电/均衡/极速档位、线程数、空闲卸载、后台生成、温度限制与终止温度、屏幕常亮、性能悬浮窗、基准测试入口
- **智能体**：智能体模式、最大工具步数、计划优先、按需载入、全局权限模式、逐工具启用与允许/询问/拒绝、技能、MCP、工作区位置
- **上下文推理**：上下文长度、滑动窗口/Token 预算/摘要/KV 位移策略、保留轮数、摘要触发比例、自动压缩、记忆与知识库开关

已接入运行链路：采样参数（含惩罚与种子）、上下文裁剪、智能体步数/工具过滤/权限、屏幕常亮、Hugging Face 下载源、系统提示词与思考提示。
温控、空闲卸载、Flash Attention/KV/批大小/mmap/预热、记忆检索、工作区文件等已持久化，待本地引擎、检索与文件工具接入后生效。

## 设置体系

- 基础功能：模型、后端、生成、性能与资源、智能体、上下文推理
- 角色：用户、角色卡、世界书、记忆、角色模型、语音、场景
- 高级功能：API 服务、MCP、插件、市场、Skill 技能、编程管理、空间管理
- 界面与外观：主题、组件
- 系统管理：权限、通知、交互、网络、接入与自动化、安全、关于、开发者选项
- 数据管理：备份、恢复

其中模型、后端、生成、性能、智能体、上下文、用户、角色卡、世界书、记忆、角色模型、语音、场景、API、MCP、插件、市场、Skill、编程管理、主题、空间、权限、关于、备份、恢复均有真实页面；
智能体页已接入权限模式与技能；模型页已接入模型档案；角色卡页可切换角色与 2D/3D；空间页可选择工作区。
Agent 页的 MCP 勾选仍使用静态列表，尚不驱动实际连接。
备份会把设置导出为 JSON 复制到剪贴板；恢复默认会清空偏好；完整数据导入与备份包尚未接入。

## 数据存储

- 设置：`SharedPreferences("iris_settings")`，通过 `SettingsRepository` 的 StateFlow 驱动 UI
- 会话：`ConversationStore` 以 JSON 存在 `filesDir/conversations.json`，流式更新合并写盘，重启后恢复；写盘失败会安全降级
- 主题：`ThemeMode`（跟随系统 / 浅色 / 深色）在 `MainActivity` 里驱动 `IrisTheme`

## 接入真实推理

在 `engine/` 下新增实现类，例如 `LlamaEngine`，把 JNI 的加载与流式解码包成 `InferenceEngine`：

```kotlin
class LlamaEngine : InferenceEngine {
    override suspend fun load(modelPath: String, config: LoadConfig, onProgress: (Float) -> Unit): ModelDetails { /* JNI */ }
    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> { /* JNI 流式回调 -> emit */ }
    override fun cancel() { /* 通知 native 停止 */ }
    override suspend fun resetContext() { /* 清 KV cache */ }
    override suspend fun unload() { /* 释放模型 */ }
    override val isLoaded: Boolean get() = /* ... */
}
```

然后只改 `AppGraph`：

```kotlin
val engines = EngineManager(settings) { backend -> LlamaEngine(backend) }
```

Agent、HTTP API、UI 均无需改动。IRIDIS 的 `engine/llama/LlamaEngine.kt`、`engine/EngineManager.kt`
可作为逐项对齐的参考实现。

## 本地 API

启动 App 后点右上角「启动 API」，默认只监听 `127.0.0.1:8686`：

```bash
curl http://127.0.0.1:8686/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"你好"}],"stream":true}'
```

## 构建（本机已验证）

- JDK：OpenJDK 17.0.18（Alpine：`apk add openjdk17-jdk`）
- Gradle：9.1.0，wrapper 指向 `/root/gradle/gradle-9.1.0-bin.zip`（可用腾讯/阿里镜像下载）
- SDK：platform 35 + build-tools 36（AGP 首次构建自动补装），`local.properties` 指向 `/root/Android`

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/root/Android ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=/root/.gradle
./gradlew assembleDebug
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`，约 59.6 MB，minSdk 31 / target 35
- `./gradlew clean assembleDebug` 实测 1 分钟级成功，Debug 签名校验通过

**ARM64 容器注意**：AGP 9 默认下载 x86_64 aapt2，在 PRoot/ARM64 上无法执行。已用仓库内
`tools/aapt2/aapt2-arm64-v8a` 替换 Gradle 缓存中的 aapt2（与 `setup_android_env.sh` 的做法一致）。
如果清理了 Gradle 缓存导致再次失败，可重新替换，或临时指定：

```bash
./gradlew assembleDebug \
  -Pandroid.aapt2FromMavenOverride=$PWD/tools/aapt2/aapt2-arm64-v8a
```

工具链为 AGP 9.0.0 / Kotlin 2.3.10 / Compose BOM 2026.01.01；依赖组合向 IRIDIS 看齐
（coroutines、lifecycle-compose、kotlinx-serialization-json、material-icons）。

## 与 IRIDIS 的差异

- 不包含 MNN/LiteRT/NPU 等额外 native 运行库；GGUF 使用预编译 libiris_llama.so
- 不使用 Koin/Hilt，依赖由 `AppGraph` 手动装配（与 IRIDIS 一致）
- 只有单个 `:app` 模块（IRIDIS 同样是单模块 + 包分层）
- `StubEngine` 仅在没有可用本地/云端引擎时兜底

## 已知限制

- 备份只包含设置 JSON，尚未提供完整备份包的导入/导出；会话持久化独立进行
- API 提供 models、chat completions、completions、health，未实现 embeddings、图片与音频端点
- MCP 客户端仅支持 Streamable HTTP（未实现 stdio/SSE 长连接与 OAuth）；MCP 服务端返回普通 JSON，不提供 SSE 流
- API/MCP Key 以明文存在 SharedPreferences，未做加密
- 技能已注册 `use_skill` 工具，但下载只支持 SKILL.md 文本 URL，未做依赖安装/版本升级
- 编程管理：PRoot 运行时与 rootfs 安装已接入；SSH 运行时仍未接入；网络/代码授权项尚未绑定到对应工具
- 工作区文件工具已接入，但 SAF 的写入/删除行为仍需在真实文件提供器上验证；移除工作区不会删除原文件夹
- 六场所 3D 场景为内置低多边形建模，尚未把用户导入的 Live2D/VRM/GLB/MMD 模型放进场景
- 综合空间的状态标签来自本地数据，3D 场景本身不驱动工具执行或智能体动作
- 角色卡支持 PNG/JSON；CHARX、WebP/JPEG 元数据卡与加密卡尚未支持
- 世界书为简化引擎：未实现递归扫描、分组评分、粘滞/冷却状态机与角色卡级绑定；全局最少激活/最大递归为配置项
- 记忆检索为本地关键词匹配，未接入向量 RAG；日记不会自动生成
- 角色模型已完成 VRM/GLB、Live2D 与 MMD 实际渲染、角色绑定、Live2D 基础表情映射与口型；MMD 动作/VMD 与 VRM 表情/口型/动作切换尚未接入
- Live2D 口型目前由生成状态与 TTS 播放状态驱动开合，尚未接入真实音频振幅；表情映射基于关键词与模型命名启发式
- 系统 TTS 已接入自动朗读，系统 STT 可在设置页测试；本地 TTS/ASR 推理、云端语音调用与聊天内语音输入尚未接入
- IndexTTS-2 约 5.9 GB，下载耗时且占用空间大；实际本地推理受设备性能限制
- 场景视频基于 VideoView，未做复杂转场/渲染；3D 底座为 Compose 绘制的视觉层
- 本地 GGUF 已接入 llama.cpp（预编译 libiris_llama.so）；MNN/LiteRT/NPU 未接入，非 arm64 设备无法加载该运行库
- 温控、空闲卸载、预热仍为配置项；预编译库不含 mmproj 视觉接口
- Hugging Face 官方源在部分网络会超时，已自动回退 hf-mirror
- 生活空间 VRM/GLB 使用 three.js + GLTFLoader、Live2D 使用 Pixi/Cubism、MMD 使用 Babylon.js + babylon-mmd WASM 运行时加载真实模型；视频通话与聊天内语音输入为界面占位
- 操作空间已接入共享权限中心、Shizuku 授权/命令桥、无障碍动作、Aries 风格虚拟屏与本机小窗；虚拟屏提供操作空间内预览和带回主屏，暂无悬浮预览/完整 IME 策略；小窗依赖 ROM freeform 支持
- ARM64 容器的 aapt2 修复依赖本机 Gradle 缓存；换机器或清缓存后需按上方说明重新指定
- 备份、世界书递归/粘滞冷却、SSH 运行时、MCP SSE/OAuth、本地 GGUF 推理与向量检索仍是后续工作；方案见下方“后续实现方向”

## 后续实现方向

| 待实现项 | 建议方案 |
|---|---|
| 完整备份 / 恢复 | 新增 `BackupService`，把设置、会话、世界书、记忆、角色卡、技能、场景序列化为带版本号的 ZIP；用 SAF `CreateDocument` / `OpenDocument` 导入导出，逐 Store 写回后刷新 StateFlow |
| 世界书运行时 | 在 `LorebookResolver` 增加递归扫描、`minActivations`、分组权重与粘滞/冷却状态机；按 conversation id 保存轮次状态；`Lorebook` 增加角色绑定字段，`ChatViewModel` 只解析全局书与当前角色绑定的书 |
| SSH 运行时 | 新增 `SshRuntime`（SSH 库连接、命令执行、超时/输出限制），由 `ShellTool` 按运行时分派；PRoot 已接入 `ProotRuntime` |
| MCP 完整协议 | 客户端持久化 `Mcp-Session-Id`、GET SSE 事件流与 `Last-Event-ID` 续传、OAuth 发现/刷新；服务端补 SSE 响应与 `notifications/tools/list_changed`；启动时自动连接已启用服务 |
| 本地 GGUF 推理 | 新增 `LlamaEngine` 与 JNI/native 库；统一 `provider.localModelPath` 与 `AppSettings.modelPath`；`LoadConfig` 接入 Flash Attention、KV 缓存、批大小、mmap 与预热 |
| 角色模型渲染 | 已接入 VRM/GLB、Live2D、MMD；后续补 MMD VMD 动作、VRM 表情与口型、真实音频振幅口型、动作切换与模型预览缓存 |
| 向量检索 | 使用 `ModelRole.EMBEDDING` 对知识文档分块并建向量索引，`MemoryStore.promptContext` 增加语义召回，保留现有关键词检索作为回退 |
| API 扩展 | 增加 `/v1/embeddings`（需要 embedding 引擎或云端代理）以及图片、音频端点；管理端补对应模型配置 |
| 操作空间 | 已接入权限中心、Shizuku、无障碍动作、Aries 风格虚拟屏与本机小窗；后续补悬浮预览、IME 策略、动作校验与禁止应用列表 |
