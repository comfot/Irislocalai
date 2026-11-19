---
name: 手机操作
description: 观察界面、按文字定位、滚动查找并校验手机操作
triggers: 操作手机,点击,滑动,滚动,打开应用,打开设置,通知,最近任务
alwaysOn: true
version: 1.1.0
suite: builtin
---

## 手机操作

1. 先用 device_ui 读取当前界面（package、nodes 的 text/desc、bounds、clickable/scrollable）。
2. 优先用 device_tap_text 按文字/描述点击；同文多处时用 match_index。
3. 目标不在屏幕内时用 device_scroll（down/up），再用 device_ui 确认。
4. 输入时先点击输入框，再 device_input_text；提交用 device_enter。
5. 每次只做一个动作；操作后用 device_wait_text 或 device_ui 校验。
6. 不要连续盲点坐标；支付、删除、卸载等不可逆操作先询问用户。
