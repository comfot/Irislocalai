# 宿主组件

这些节点由宿主 Compose 渲染，插件只传 props 和回调。

## HostChat

复用主聊天（消息列表 + 输入框 + 发送）。

```js
ctx.UI.HostChat({
  title: "与主聊天对话",
  placeholder: "输入消息",
  height: 320,
  readOnly: false
})
```

可选 props：`title`、`placeholder`、`height`、`readOnly`。发送会写入当前主聊天；插件面板需要在聊天页打开。

## HostCharacter3DView

复用 3D 角色渲染。

```js
ctx.UI.HostCharacter3DView({
  modelId: "可选，缺省用当前激活模型",
  portrait: false,
  interactive: true,
  height: 320
})
```

支持 VRM / GLB / MMD；Live2D 请用 WebView 或 `AiChat` 之外的页面能力。

## HostModelPicker

读取角色模型库并回调选择。

```js
ctx.UI.HostModelPicker({
  title: "选择模型",
  selected: ctx.useState("model", "")[0],
  onSelect: function (modelId, model) {
    ctx.setState("model", modelId);
    ctx.showToast("已选择 " + model.name);
  }
})
```

模型对象包含 `id`、`name`、`type`。

## HostModelProfilePicker

选择模型档案（`ModelProfile`）：

```js
ctx.UI.HostModelProfilePicker({
  title: "选择模型档案",
  selected: "profile-id",
  onSelect: function (item) { ctx.showToast("选择 " + item.name) }
})
```

## HostScenePicker

选择生活空间场景：

```js
ctx.UI.HostScenePicker({ title: "选择场景", onSelect: function (item) { } })
```

## HostCharacterPicker

选择角色卡：

```js
ctx.UI.HostCharacterPicker({ title: "选择角色卡", onSelect: function (item) { } })
```

三个选择器回调收到的 JSON 都包含 `id` 与 `name`。

## HostModelProviderPicker

选择模型提供商，回调 `{id,name}`。

## HostWorkspacePicker

选择工作区，回调 `{id,name}`。

## HostToolList

只读展示当前注册的工具列表：

```js
ctx.UI.HostToolList({ title: "可用工具", limit: 40 })
```

## HostSceneBackground

渲染当前或指定场景的背景：

```js
ctx.UI.HostSceneBackground({ sceneId: "可选", height: 180 })
```

## HostMemorySearch

输入查询并调用记忆检索：

```js
ctx.UI.HostMemorySearch({ title: "检索记忆", placeholder: "上次的方案", limit: 6 })
```

## HostSkillPicker

选择技能，回调 `{id,name}`。

## HostLorebookPicker

选择世界书，回调 `{id,name}`。

## HostMemoryList

只读展示记忆库：

```js
ctx.UI.HostMemoryList({ title: "最近记忆", limit: 20 })
```

## HostCharacterAvatar

显示当前或指定角色卡头像：

```js
ctx.UI.HostCharacterAvatar({ characterId: "可选", size: 72 })
```

## HostStatus

显示当前空间、引擎与记忆数量。
