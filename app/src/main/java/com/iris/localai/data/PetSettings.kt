package com.iris.localai.data

/** 桌宠单击行为。 */
enum class PetTapAction(val label: String) {
    BUBBLE("随机台词"),
    OPEN_APP("显示对话栏"),
    NONE("不响应"),
}

/** 桌宠长按行为；选择 MENU 时弹出喂食/唱歌/叫醒菜单。 */
enum class PetLongPressAction(val label: String) {
    MENU("互动菜单"),
    OPEN_APP("打开对话"),
    HIDE("隐藏桌宠"),
}
