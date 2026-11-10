# IRIS LocalAI

IRIS LocalAI 是运行在 Android 上的本地 AI 工作台：在一个应用里完成本地 GGUF 推理、云端模型接入、Agent 工具调用、角色陪伴、内容创作与设备操作。应用采用单模块 `:app`，Kotlin + Jetpack Compose 构建，依赖由 `AppGraph` 手动装配。

- 应用名：`IRIS LocalAI`
- 包名 / Application ID：`com.iris.localai`
- 当前版本：`0.1.0`（versionCode `1`）
- 模块：单模块 `:app`
- SDK：minSdk `31`，compileSdk / targetSdk `35`
- 界面：Jetpack Compose + Material 3，四空间布局
- 推理：llama.cpp（GGUF）本地推理 + OpenAI 兼容云端流式推理
- 服务：内置 OpenAI 兼容 HTTP API 与 MCP 服务端

## 功能总览

### 对话与四空间

- **工作空间**：面向编码、文件与命令任务，支持工作区文件夹、附件、工具调用卡片、流式输出、停止生成与会话自动命名。
- **生活空间**：角色舞台与陪伴对话，支持场景背景、舞台底座、Live2D / VRM / GLB / MMD 实际渲染、表情/动作/口型与视频通话界面。
- **操作空间**：权限中心、执行方式、执行日志与常用指令，面向点击、滑动、输入、截图等手机操作。
- **综合空间**：离线 3D 园区与五个场所（工作室、图书馆、工地、值班室、前台），支持拖动旋转、双指缩放、楼栋点击与状态标签。
- 会话按空间隔离，统一持久化到 `conversations.json`；切换空间会切换对应会话与输入栏。
- 视频通话会持续采集前置摄像头画面和麦克风，本地或云端 ASR 转写后连同画面帧进入多模态对话，回复再由本地、云端或系统 TTS 朗读，形成实时语音视频对话。
- 会话归档：侧栏会话菜单可归档/恢复；归档会话从活跃列表移入「已归档」区，归档时触发一次记忆沉淀；删除归档会话只删除会话记录，不删除已沉淀记忆。
- 侧栏包含会话列表、可折叠工作区与角色卡横排；顶栏可切换空间，设置入口位于侧栏底部。

### 模型与推理

- **本地 GGUF**：通过预编译 `libiris_llama.so` 加载 llama.cpp，支持 CPU 与 OpenCL GPU 后端，GPU 失败可按开关回退 CPU。
- **云端模型**：OpenAI、Anthropic、Gemini、DeepSeek、OpenRouter 与自定义 OpenAI 兼容端点，按 SSE 解析流式增量。
- **多模型档案**：模型提供商独立管理，可添加本地/云端提供商并限定语言、视觉、创作、Embedding、动作类型；档案只选择每个角色的提供商、模型与高级参数。提供商名称唯一，云端支持拉取 `/models`，模型列表可勾选启用并长按配置识图/音频/视频/联网能力。
- **档案运用**：档案可设为“运用全局配置”，或指定到工作空间与生活空间角色卡；全局开启时“运用到”自动停用。切换档案只改变角色模型配置，不影响提供商。
- **多模态输入**：开启提供商「支持识图」后，图片附件按视觉模型以 `image_url` 内容发送。
- **本地模型市场**：Hugging Face 搜索、GGUF 断点续传、官方源失败回退 hf-mirror、ModelScope 备用检索，并内置推荐模型目录。
- **角色模型广场**：聚合模之屋、Bowlroll、BOOTH、VRoid Hub 等站点与创作者名录，支持站点搜索和链接导入。

### Agent 与工具链

- `AgentLoop` 采用 ReAct 风格循环，解析 `<tool_call>{...}</tool_call>`，兼容模型原生 DSML 工具标签；默认最多 4 步，可在设置中调整。
- `ToolRegistry` 注册 22 个内置工具：`current_time`、`device_info`、`random_pick`、`use_skill`、`workspace`、`read_file`、`write_file`、`edit_file`、`list_files`、`search_files`、`fetch_url`、`memory`、`motion_control`、`expression_control`、`shell`、`device_open_app`、`device_tap`、`device_swipe`、`device_back`、`device_home`、`device_input_text`、`device_screenshot`。
- 工具可按空间过滤：文件工具与命令工具只在工作/综合空间生效；设备操作工具只在操作/综合空间生效。
- 工具权限支持允许 / 询问 / 拒绝，Shell 另支持允许与禁止的命令前缀。
- 插件工具、MCP 客户端工具与技能工具会动态注册进同一工具表。

### 角色与内容

- **角色卡**：PNG/JSON 的 V1/V2/V3 与春水别名导入，创建、编辑、收藏、删除、导出 JSON/PNG，列表与缩略图布局。
- **角色模型**：Live2D ZIP、VRM 0.x/1.0、GLB/GLTF、MMD（PMX/PMD）导入、缩略图、模型绑定与位置偏移。
- **世界书**：SillyTavern world info 导入，支持主/二级关键词、整词/正则/大小写、常驻、概率、顺序、插入位置/深度、粘滞/冷却、扫描深度与 Token 预算。
- **记忆三库**：日记、记忆库、知识库；常规对话只注入记忆摘要，需要回忆时由 `memory` 工具做向量 + 关键词混合检索。日记按应用打开过的日期自动生成，记忆库支持文件夹、标题、标签、重要度、搜索、详情与编辑。
- **记忆关系图**：记忆之间支持 `SHARED_TAG` / `SAME_FOLDER` 自动连边和手动关系，关系图支持拖拽缩放、节点点击与跨文件夹虚线；归档会话时从对话中沉淀事实、偏好与事件，记忆页也提供“整理最近对话”手动入口。
- **场景**：背景图/视频、名称/介绍、底座样式（无/圆形舞台/网格平台/光晕底座/纹理）与颜色。
- **语音**：系统 TTS/STT；云端支持 OpenAI 兼容 TTS/ASR，重点接入小米 MiMo `mimo-v2.5-tts-voiceclone` 音色复刻（wav/mp3 样本，≤10 MB）与 `mimo-v2.5-asr`；本地通过 sherpa-onnx 运行 Kokoro/MeloTTS 合成与 SenseVoice/Paraformer 识别，自动朗读、语音输入和视频通话都按所选引擎分流。
- **桌面宠物**：悬浮桌宠服务，支持模型、缩放、拖动、点击/长按动作、待机动画与聊天气泡。

### 扩展能力

- **SKILL**：`SKILL.md`（YAML frontmatter + Markdown）导入、创建、编辑、删除、URL 下载、启用与提示词注入；注册 `use_skill` 工具按需读取。
- **插件**：兼容 Operit ToolPkg（`manifest.json` / `manifest.hjson` + 子包 METADATA），WebView 沙箱执行脚本，限制文件与网络访问，支持 Hook 与工具注册。
- **MCP 客户端**：支持 Streamable HTTP、旧版 HTTP+SSE 长连接与 PRoot 容器内 stdio；OAuth 支持 Access/Refresh Token 与 401 自动刷新；工具注册为 `mcp_<server>_<tool>`。
- **MCP 服务端**：把已注册工具暴露为 MCP 工具（`shell`、`workspace` 与 `mcp_` 前缀工具不对外），POST 可按 Accept 返回 SSE，GET 可建立事件流，默认 `http://127.0.0.1:8765/mcp`。
- **本地 API**：OpenAI 兼容 `/v1/models`、`/v1/chat/completions`、`/v1/completions`、`/v1/embeddings`、`/v1/images/generations`、`/v1/audio/speech`、`/v1/audio/transcriptions` 与 `/health`；文本支持 SSE，语音合成返回 wav，识别接受 JSON Base64 或 multipart 音频。默认只监听 `127.0.0.1:8686`。
- **市场**：模型、角色模型、插件、语音包、舞台素材与动作素材的统一下载入口。

### 设备操作与编程

- **无障碍服务**：点击、滑动、返回、桌面、文本输入与截图。
- **Shizuku**：授权申请、shell 命令执行、自动开启无障碍；后台/Shizuku 模式优先走 Shizuku，失败回退无障碍。
- **后台运行方式**：普通屏幕、Aries 风格虚拟屏、本机 freeform 小窗；虚拟屏使用 ImageReader 收帧与定向事件注入。
- **应用 Shell**：在应用内工作区执行 `/system/bin/sh`，带超时、输出上限与命令前缀限制。
- **PRoot 容器**：随包 proot 启动器 + rootfs，安装 Alpine / Ubuntu / Debian / 自定义镜像，rootfs 位于 `filesDir/proot/<imageId>/linux`，工作区挂载到容器 `/workspace`。
- **工作区文件工具**：应用内工作区使用 `File` + canonical 路径校验；SAF 工作区使用 `ContentResolver` + `DocumentsContract`。

### 通知与外观

- 生成完成通知，支持锁屏可见、悬浮提醒与灵动岛样式悬浮层。
- 主题支持跟随系统、浅色、深色；综合空间强调色与明暗主题实时传入 3D 场景。
- 设置体系共 7 个分组、37 个入口（含 3 个二级隐藏页），覆盖模型、后端、生成、性能、智能体、上下文、角色、语音、场景、API、MCP、插件、市场、技能、编程、权限、存储、通知与数据管理。

## 技术架构

```text
Compose UI
  ├── ChatViewModel / LifeSpace / CompositeSpace / OperateSpace
  ├── SettingsViewModel + 设置页集合
  └── WebView 渲染桥（Live2D / three.js / Babylon.js / MMD / 3D 园区）

AppGraph（手动依赖装配）
  ├── EngineManager
  │     ├── LlamaEngine（JNI + libiris_llama.so）
  │     ├── OpenAiCompatibleEngine（云端 SSE）
  │     └── StubEngine（无模型时回声兜底）
  ├── AgentLoop + ToolRegistry
  ├── ConversationStore / MemoryStore / WorldBookStore
  ├── CharacterCardStore / CharacterModelStore / SceneStore
  ├── ModelProfileStore（模型提供商 + 配置档案）
  ├── TtsController / CloudVoiceClient / LocalVoiceRuntime
  ├── MotionAssetStore / VrmaMotionStore / HastMotionStore
  ├── SkillStore / PluginStore / PluginRuntime
  ├── McpStore / McpConnectionManager / McpServerService
  ├── ProotRuntime / RootfsInstaller / ShellTool
  ├── DevicePermissionStore / DeviceActionExecutor / VirtualDisplayController
  ├── ApiService / HttpServer / ApiRoutes
  └── AppStorage / SettingsRepository / SettingsMirror
```

### 关键设计

- **模型提供商与档案分离**：`ModelProfileStore` 把提供商（密钥、端点、模型列表与能力）和配置档案（角色选择与生成参数）分开持久化；旧档案中的提供商在首次启动时迁移为独立提供商并立即落盘。
- **手动依赖装配**：项目不使用 Koin/Hilt，所有单例由 `AppGraph` 构造并注入，`IrisApp` 持有唯一依赖图。
- **引擎抽象**：`InferenceEngine` 定义加载、生成、取消、重置上下文与卸载；`EngineManager` 统一暴露 `EngineState` 与事件流。
- **Agent 循环**：`AgentLoop` 负责系统提示、工具调用解析、工具执行、结果回填与步数限制；工具实现只依赖 `Tool` 契约。
- **零依赖 HTTP**：`HttpServer` 基于 Socket 实现 HTTP/1.1、SSE 与请求解析，不引入 OkHttp/Ktor。
- **状态驱动 UI**：各 Store 暴露 `StateFlow`，Compose 通过 `collectAsStateWithLifecycle` 订阅；设置变更即时反映到侧栏、生活空间与工具过滤。
- **WebView 渲染**：模型与贴图通过虚拟 `https://iris.local/...` 源由 `WebViewClient.shouldInterceptRequest` 流式返回，避免 `file://` 下 fetch 限制。
- **存储隔离**：新数据写入外置应用专属目录，旧私有目录作为兼容读取根；设置通过 JSON 镜像双向同步。

### 核心模块

- **engine/**：`EngineManager` 统一加载/卸载与状态；`LlamaEngine` 通过 JNI 调用 `libiris_llama.so`，`OpenAiCompatibleEngine` 解析云端 SSE 并按图片输入切换到视觉提供商，`StubEngine` 在无可用模型时回声兜底。
- **agent/**：`AgentLoop` 负责系统提示、ReAct 循环、工具调用解析、权限判断与结果回填；`ConversationStore` 按空间管理会话；`AssistantMessageParser`、`ThinkTagFilter`、`DisplayTagFilter` 处理思考标签与展示内容。
- **character/**：`CardCodec`/`CardSpec` 解析 PNG/JSON 角色卡，`CharacterCardStore` 管理卡与收藏，`CharacterModelStore` 管理 Live2D/VRM/GLB/MMD，`CharacterPlazaClient` 聚合角色模型站点。
- **memory/**：日记、记忆库、知识库、记忆关系、打开日期与自动沉淀状态；`briefContext` 生成精简摘要供常规注入，`recall` 使用本地哈希向量或已配置的云端 Embedding 做详细检索；日记按打开日期自动生成，关系图与自动记忆分别由 `MemoryLink` 和 `MemoryAutoCapture` 维护。
- **lorebook/**：世界书条目解析、关键词/正则匹配、预算裁剪与系统提示拼接。
- **motion/**：`MotionAssetStore` 管理 VMD/镜头/BGM，`VrmaMotionStore` 管理 VRMA，`HastMotionStore` 管理 HAST-SEC，`MotionBridge` 把动作指令送进 WebView，`MotionEnvironment` 通过 PRoot 运行 Node 动作服务。
- **plugin/**：`PluginManifestParser` 解析 Operit ToolPkg，`PluginRuntime` 用 WebView 沙箱执行脚本，`PluginTool` 把插件工具注册进 Agent，`OperitMarketClient` 读取市场索引。
- **permission/**：`DevicePermissionStore` 汇总无障碍、悬浮窗、录音、通知与 Shizuku 状态，`DeviceActionExecutor` 执行设备动作，`VirtualDisplayController` 管理虚拟屏，`FreeformLaunchActivity` 启动小窗。
- **proot/**：`ProotRuntime`/`ProotSession` 启动容器，`RootfsInstaller` 下载与解压 rootfs，`RootfsPatcher` 补 DNS/hosts/locale。
- **server/**：`HttpServer` 提供零依赖 HTTP/1.1、SSE 与二进制响应，`ApiRoutes` 实现 OpenAI 兼容路由（含 embeddings、图片生成代理、语音合成与转写），`ApiAuth` 处理 Key、Origin 与回环限制。
- **storage/**：`AppStorage` 定义外置写根与私有读根，`SettingsMirror` 负责 `settings.json` 的双向同步。

## 目录结构

```text
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── studio.html / studio.js       # 综合空间 3D 园区与五场所
│   ├── three.min.js                  # 综合空间离线 three.js
│   ├── character.html / character.js # VRM/GLB 页面与加载
│   ├── live2d.html / live2d.js       # Live2D 页面
│   ├── mmd.html / mmd.js             # MMD 页面
│   ├── motion/server.js              # PRoot 内置动作服务
│   └── model/                        # three.js、GLTFLoader、Cubism、Pixi、Babylon、MMD 运行时
├── java/com/iris/localai/
│   ├── IrisApp.kt                    # Application，持有 AppGraph
│   ├── AppGraph.kt                   # 手动装配与工具注册
│   ├── MainActivity.kt               # 唯一 Activity
│   ├── accessibility/                # 无障碍服务
│   ├── agent/                        # AgentLoop、Tool、ToolCallParser、会话与消息解析
│   │   └── tools/                    # 时间、设备、文件、网络、记忆、动作、Shell 等工具
│   ├── character/                    # 角色卡编解码、存储、模型仓库、角色广场客户端
│   ├── code/                         # 编程设置、ShellTool
│   ├── data/                         # 设置、模型档案、下载客户端、语音配置、设置镜像
│   ├── engine/                       # EngineManager、InferenceEngine、云端引擎、Stub
│   │   └── llama/                    # LlamaNative / LlamaEngine
│   ├── lorebook/                     # 世界书解析与存储
│   ├── mcp/                          # MCP 客户端、连接管理、服务端与存储
│   ├── memory/                       # 日记、记忆库、知识库
│   ├── motion/                       # VMD/VRMA/BGM、动作绑定、HAST、PRoot 动作服务
│   ├── notify/                       # 通知与灵动岛悬浮层
│   ├── permission/                   # 权限中心、Shizuku、无障碍动作、虚拟屏、freeform
│   ├── pet/                          # 桌宠服务、悬浮内容与桥接
│   ├── plugin/                       # ToolPkg 解析、存储、WebView 运行时、市场客户端
│   ├── proot/                        # ProotRuntime、Session、rootfs 安装与补丁
│   ├── scene/                        # 场景配置与舞台素材目录
│   ├── server/                       # HTTP/SSE、OpenAI 路由、鉴权、服务生命周期
│   ├── skill/                        # SKILL.md 解析与存储
│   ├── storage/                      # AppStorage 存储根与双根读取
│   ├── ui/
│   │   ├── MainScreen.kt
│   │   ├── chat/                     # 工作/生活/操作/综合空间、聊天组件、模型视图
│   │   ├── settings/                 # 设置首页、37 个入口页面与各 ViewModel
│   │   └── theme/                    # 配色、字体与主题
│   ├── voice/                        # 系统 TTS、sherpa-onnx 本地 TTS/ASR、MiMo 云语音、录音与模型下载
│   └── workspace/                    # 工作区条目、应用内/SAF 文件读写
├── jniLibs/
│   ├── arm64-v8a/libiris_llama.so    # llama.cpp 推理库
│   ├── arm64-v8a/libproot_*.so       # ARM64 PRoot 启动器
│   └── x86_64/libproot_*.so          # x86_64 PRoot 启动器
└── res/
```

当前 Kotlin 源码约 179 个文件、4.2 万行。单元测试位于 `app/src/test/`，覆盖 API 鉴权、角色卡编解码、技能解析、工具注册、消息解析、VPD→VMD、权限状态、插件清单与 PRoot rootfs 等。

## 四空间

| 空间 | 主界面 | 输入栏 | 主要能力 |
|---|---|---|---|
| 工作空间 | AI 对话、Markdown、思考过程、工具卡片 | 工作区 / 模型 / 权限 / 工具模式 / 附件 / 语音 / 发送 | 文件工具、Shell、PRoot、任务执行 |
| 生活空间 | 角色舞台、场景背景、Live2D/VRM/GLB/MMD | 文本 / 语音 / 视频通话 / 表情 | 角色陪伴、表情动作、口型、场景 |
| 操作空间 | 权限中心、执行方式、执行日志 | 文本 / 语音 / 快捷指令 | 点击、滑动、输入、截图、应用启动 |
| 综合空间 | 六场所 3D 园区与工作室 | 无输入栏 | 场所切换、状态标签、最近会话 |

工作区是真实文件夹：可通过系统文件管理器选择 SAF 文件夹并持久化权限，也可在应用容器内创建 `filesDir/workspaces/<name>`。每个会话记住自己的工作区；移除工作区条目不会删除文件夹内容。

## 数据与存储

### 外置存储根

新下载或导入的模型、素材与角色卡写入：

```text
/storage/emulated/0/Android/data/com.iris.localai/files/IRIS
```

| 目录 | 内容 |
|---|---|
| `models/` | Hugging Face / ModelScope 下载的 GGUF |
| `characters/` | 角色卡 `card.json` + 头像 |
| `character_models/` | Live2D / VRM / GLB / MMD 角色模型 |
| `pet_models/` | 桌宠模型 |
| `voice_models/` | 已安装语音包与 `.installed` 标记 |
| `motion_assets/` | VMD 动作、镜头、BGM、VRMA |
| `stage_assets/` | 舞台背景与纹理 |
| `scenes/` | 场景背景图/视频 |
| `settings/settings.json` | 设置镜像 |
| `mcp/servers.json` | MCP 客户端服务配置（含明文请求头） |

外置目录通过 `Context.getExternalFilesDir(null)` 获取，不需要 `MANAGE_EXTERNAL_STORAGE`。旧版本位于 `filesDir` 的同名数据不会自动迁移，但会作为兼容读取根继续参与扫描与加载，外置同名条目优先。

### 应用私有数据

| 路径 | 内容 |
|---|---|
| `filesDir/conversations.json` | 四空间全部会话与消息 |
| `filesDir/memory/{diary,memories,knowledge}.json` | 日记、记忆库、知识库 |
| `filesDir/memory/{usage_days,diary_handled}.json` | 应用打开日期与已处理日记日期 |
| `filesDir/memory/links.json` | 记忆关系边（源/目标/类型/权重/说明） |
| `filesDir/memory/auto_state.json` | 每个会话最后处理的自动沉淀消息 id |
| `filesDir/skills/<id>/SKILL.md` + `meta.json` | 技能库 |
| `filesDir/plugins/<id>/` | 已安装插件 |
| `filesDir/plugin_config/<pluginId>/` | 插件配置 |
| `filesDir/proot/<imageId>/linux` | PRoot rootfs |
| `filesDir/workspaces/<name>/` | 应用内工作区 |
| `filesDir/attachments/` | 聊天图片附件 |
| `filesDir/screenshots/` | 设备操作截图 |
| `filesDir/avatars/` | 用户头像 |
| `filesDir/voice_clones/` | MiMo voiceclone 导入的音频样本 |
| `filesDir/hast/` | HAST-SEC 动作模型与轨迹 |
| `filesDir/motion_runtime/` | PRoot 动作服务运行时脚本 |
| `shared_prefs/iris_*.xml` | 设置、模型档案、世界书、场景、MCP、技能、工作区等索引 |

### 设置镜像

`settings/settings.json` 是 `iris_settings` 的可读镜像：

- 应用启动时若文件修改时间晚于上次写入，会导入用户编辑；应用内每次修改设置后自动重写。
- 文件为 pretty JSON，可在文件管理器中查看和备份。
- `api_key`、`motion_api_key`、`asset_token`、`tts_cloud_key`、`stt_cloud_key` 不导出、不导入。
- JSON 解析失败会保留为 `settings.json.invalid-<时间戳>`，不会静默丢弃。
- 应用运行期间直接编辑可能被下一次设置变更覆盖；建议关闭应用后编辑，再重启或到「设置 › 系统管理 › 存储与外置文件」点“重新加载”。

> `Android/data` 在 Android 11+ 的 DocumentsUI 中默认隐藏，Android 14 起限制更严格。文件已正确写入上述路径，但系统文件管理器可能看不到；可通过支持 Shizuku/ADB 的文件管理器、ADB 或连接电脑访问。外置应用专属目录会随应用卸载删除。

## 构建与运行

### 环境

- JDK 17
- Android SDK Platform 35 + Build Tools
- 首次构建需要访问 JitPack 与 GitHub Release（下载 sherpa-onnx 1.13.8 完整 AAR）
- Gradle 9.1.0（仓库 wrapper 的 `distributionUrl` 指向本地 `/root/gradle/gradle-9.1.0-bin.zip`，其他环境请改成可访问的 Gradle 发行包）
- Android SDK 路径通过 `local.properties` 的 `sdk.dir` 指定

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/root/Android
export ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=/root/.gradle

./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，当前约 128 MB（完整 sherpa AAR 包含全部 ABI），minSdk 31 / target 35。

单元测试：

```bash
./gradlew testDebugUnitTest
```

### ARM64 容器说明

AGP 9 默认下载的 aapt2 在 PRoot/ARM64 上可能无法执行。仓库提供 `tools/aapt2/aapt2-arm64-v8a`，当前环境的 Gradle 缓存已配置为可用的 ARM64 版本，直接执行 `./gradlew assembleDebug` 即可。不要在同一次构建中再传 `-Pandroid.aapt2FromMavenOverride`，AGP 9 会校验并导致失败；若清理缓存后需要重新配置，按 `setup_android_env.sh` 的方式替换缓存中的 aapt2。

## 已知限制

- **推理后端**：本地仅接入 llama.cpp GGUF；MNN、LiteRT 与 NPU 尚未接入。`libiris_llama.so` 只提供 arm64-v8a，x86_64 仅带 PRoot 启动器。
- **语音**：系统 TTS 与 SpeechRecognizer 可用；云端 TTS/ASR 已接通 MiMo 与 OpenAI 兼容端点；本地 sherpa-onnx 支持 Kokoro/MeloTTS 合成与 SenseVoice/Paraformer 识别。sherpa AAR 不随仓库内置，由 Gradle 首次构建从 JitPack 下载完整包。IndexTTS-2 因缺少 Python 运行时未加入本地引擎。
- **MCP**：客户端支持 Streamable HTTP、旧版 HTTP+SSE 与 stdio；stdio 依赖已安装的 PRoot rootfs 与应用内工作区；OAuth 需填写 Access/Refresh Token、Token URL 与客户端信息，未实现浏览器授权码与动态注册；服务端支持 SSE 响应与 GET 事件流，但仍是 close 分隔、无 chunked 编码；MCP 连接需手动建立。
- **API**：已提供 embeddings、图片生成代理与音频合成/转写。语音端点使用语音设置中的云端配置；图片生成把请求代理到当前语言模型提供商的 `/images/generations`，是否可用取决于提供商与模型；未实现音频实时流式端点。
- **备份**：当前仅把设置 JSON 复制到剪贴板，没有完整备份包导入/导出；恢复页只支持恢复默认设置。
- **世界书**：递归扫描、分组评分、粘滞/冷却状态机与角色卡级绑定仍为简化实现。
- **记忆检索**：默认使用本地特征哈希向量，配置模型档案的 OpenAI 兼容 Embedding 角色后会改用云端 `/embeddings`；两者都失败时回退关键词评分。日记由规则摘要生成，不是 LLM 代写。
- **记忆关系图**：没有引入 ObjectBox，使用 JSON 复刻 Operit 的节点/边语义；自动连边依据共享标签或同文件夹，手动关系和自动沉淀的模型抽取关系都会写入 `links.json`。
- **自动沉淀**：归档会话时触发一次当前引擎的结构化记忆抽取；引擎不可用、失败或返回非 JSON 时回退用户陈述规则提取。记忆页“整理最近对话”也可手动触发。删除归档会话不会删除已写入的记忆。自动抽取质量受模型能力影响。
- **工作区位置**：`workspaceLocation` / `customWorkspacePath` 已持久化，但 Shell、PRoot 与文件工具仍以 `WorkspaceStore` 的活动工作区为准。
- **角色模型**：VRM/GLB、Live2D、MMD 已接入实际渲染；MMD VMD 动作编排、VRM 表情/动作切换与更多口型细节仍有缺口。TTS 播放已通过 Visualizer 提供真实振幅并驱动 Live2D 口型。
- **视频通话**：已是本地采集加云端多模态对话链路（相机帧 + 麦克风 → ASR → 对话 → TTS），不是 WebRTC/P2P 或运营商级实时音视频；识别与合成延迟取决于云端服务。
- **桌宠与虚拟屏**：桌宠依赖悬浮窗权限；虚拟屏依赖 Shizuku + HiddenApiBypass，freeform 依赖 ROM 支持。
- **插件兼容**：Operit Compose DSL UI 无法在 WebView 渲染；依赖任意 Java 反射、`Java.implement`、`loadDex/loadJar` 或宿主单例的插件无法等价运行。
- **本地语音模型**：sherpa-onnx 1.13.8（Apache-2.0），Gradle 坐标 `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8`，首次构建从 JitPack 下载完整 AAR；官方 release 下载地址：`https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar`。Kokoro/MeloTTS 与 SenseVoice/Paraformer 按各自模型作者的许可使用，首次使用需在设置中下载 pinned revision 的模型文件。
- **安全**：API Key、云语音 Key、MCP 请求头与模型提供商 Key 等以明文存入 SharedPreferences；MCP 服务清单还会镜像到外置的 `mcp/servers.json`；外置目录可被具备文件访问能力的其他应用读取。
- **存储可见性**：`Android/data` 路径在 DocumentsUI 中可能不可见；外置应用专属目录会随卸载删除，重要数据需自行备份。
- **素材许可**：HAST-SEC 为非商用研究许可；角色模型、Live2D、MMD 与舞台素材请遵守各自作者许可。

## 参考项目与第三方资源

- [IRIDIS](https://github.com/WWRJ3321/IRIDIS)：工程结构、设置分层与推理/Agent/服务分层参照。
- [xiaokeai](https://github.com/liupig/xiaokeai)：3D 舞台与场景素材概念，舞台素材采用 Apache-2.0 公开仓库。
- Operit ToolPkg 与 Operit 市场：插件清单、子包 METADATA 与市场索引兼容。
- AIRI、MikuMikuAR、agent-workspace：角色模型、角色广场、动作与工作空间交互的参考。
- 内置 Web 运行时：three.js（MIT）、Babylon.js、pixi-live2d-display（MIT）、Live2D Cubism Core（Live2D 专有许可），许可文本位于 `app/src/main/assets/model/LICENSE-*.txt`。
