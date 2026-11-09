package com.iris.localai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMessageParserTest {

    @Test
    fun parsesMainTextAndStatusCard() {
        val raw = """
            <maintext>
            动作跑起来了，角色动了一下。
            </maintext>
            <Status_block>
            『📅 日期：11月9日 星期一 | ⏰ 时间：晚上21点43分 | 📍 位置：室内，3D 视图前』
            <details><summary>[角色状态]</summary>
            - 👧 岚的状态
              - 🧑‍💼 当前行动：站在 3D 视图前
              - 👔 当前穿搭：深色针织衫／长裤
              - 💭 当前内心：*test 居然是个通用动作……*
            </details>
            </Status_block>
        """.trimIndent()

        val display = AssistantMessageParser.parse(raw)
        assertEquals("动作跑起来了，角色动了一下。", display.mainText)
        val status = display.status
        requireNotNull(status)
        assertEquals("11月9日 星期一", status.date)
        assertEquals("晚上21点43分", status.time)
        assertEquals("室内，3D 视图前", status.location)
        val items = status.sections.flatMap { it.items }
        assertTrue(items.any { it.label.contains("当前行动") && it.value == "站在 3D 视图前" })
        assertTrue(items.any { it.label.contains("当前内心") && it.value.contains("通用动作") })
    }

    @Test
    fun supportsPartialStatusWhileStreaming() {
        val display = AssistantMessageParser.parse(
            "<maintext>正文</maintext><Status_block>『日期：今天 | 时间：晚上』- 当前行动：刚播放完",
        )
        assertEquals("正文", display.mainText)
        assertEquals("今天", display.status?.date)
        assertEquals("晚上", display.status?.time)
        assertTrue(display.status?.sections?.flatMap { it.items }?.any { it.value == "刚播放完" } == true)
    }

    @Test
    fun keepsPlainTextWithoutStatus() {
        val display = AssistantMessageParser.parse("直接回答")
        assertEquals("直接回答", display.mainText)
        assertNull(display.status)
    }
}
