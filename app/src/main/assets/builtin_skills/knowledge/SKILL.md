---
name: 知识库
description: 检索本地文档与记忆
triggers: 知识库,记忆,回忆,上次,之前
version: 1.1.0
suite: builtin
---

## 知识库与记忆

常规对话只携带记忆摘要。需要确认用户过去说过的事、偏好或历史细节时，调用 memory 工具：

```json
{"op":"recall","query":"要回忆的内容"}
```

规则：先检索再回答；检索不到就说明不确定，不要编造；只有用户明确要求记住时才调用 op=save。
