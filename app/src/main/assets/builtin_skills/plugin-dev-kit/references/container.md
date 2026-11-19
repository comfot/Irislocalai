# PRoot 容器

插件可以调用宿主提供的容器终端；需要用户已在「编程管理」开启命令执行并安装 rootfs。

```js
const session = await ctx.callTool("plugin_dev", { op: "status" }); // 仅示例：查状态
// 实际终端接口在插件工具代码中使用：
const created = JSON.parse(irisHost.terminalCreate("my-session"));
const result = JSON.parse(irisHost.terminalExec(created.id, "uname -a", 30000));
```

说明：

- `terminalCreate(name)` 返回会话 id；同一个 id 复用工作目录与环境变量。
- `terminalExec(sessionId, command, timeoutMs)` 返回 `{ exitCode, output }`，超时会有标记。
- 命令在工作区挂载的容器内执行，输出上限约 200 KB。
- 镜像未安装、开关关闭或超时会返回错误文本，不要假设命令一定成功。
- 容器能力较强，只用于用户明确要求的任务；不要执行破坏性命令。
