# 插件 UI DSL

UI 模块导出一个函数（`module.exports = function (ctx) {...}`，也兼容 `exports.screen`），返回节点树；变更时宿主会重新渲染。

```js
exports.screen = function (ctx) {
  const [name, setName] = ctx.useState("name", "");
  const [busy, setBusy] = ctx.useState("busy", false);
  return ctx.UI.Column({ fillMaxWidth: true }, [
    ctx.UI.Text({ text: "你好", bold: true }),
    ctx.UI.OutlinedTextField({
      label: "名字",
      value: name,
      onValueChange: function (value) { setName(value); }
    }),
    ctx.UI.Button({
      text: busy ? "处理中" : "提交",
      enabled: !busy,
      onClick: async function () {
        setBusy(true);
        try {
          const result = await ctx.callTool("plugin_dev", { op: "status" });
          ctx.showToast(result);
        } finally { setBusy(false); }
      }
    })
  ]);
};
```

可用组件：

- 布局：`Column`、`Row`、`Box`、`LazyColumn`、`LazyRow`、`FlowRow`、`FlowColumn`、`Card`、`Surface`、`Spacer`、`Divider`
- 文本：`Text`、`Markdown`
- 输入：`TextField` / `OutlinedTextField`、`Switch`、`Checkbox`、`Slider`、`RadioButton`
- 操作：`Button`、`TextButton`、`OutlinedButton`、`IconButton`
- 展示：`Icon`、`Image`、`LinearProgressIndicator`、`CircularProgressIndicator`、`AlertDialog` / `Dialog`
- 复用宿主：`AiChat`、`HostChat`、`HostCharacter3DView`、`HostCharacterAvatar`、`HostModelPicker`、`HostModelProfilePicker`、`HostModelProviderPicker`、`HostScenePicker`、`HostSceneBackground`、`HostCharacterPicker`、`HostWorkspacePicker`、`HostToolList`、`HostMemorySearch`、`HostMemoryList`、`HostSkillPicker`、`HostLorebookPicker`、`HostStatus`、`WebView`

事件：`onClick`、`onValueChange`、`onCheckedChange`、`onConfirm`、`onDismiss`。`ctx` 提供 `useState`、`useRef`、`useMemo`、`callTool`、`host.invoke`、`showToast`、`getEnv`、`setEnv`、`navigate`。

限制：没有文件选择器、SAF、原生对话框和任意 Compose 代码；复杂图表/动画用 `WebView` + HTML。`WebView` 可用 `controller` 调用 JS 接口，用 `onInterceptRequest` 返回本地资源。
