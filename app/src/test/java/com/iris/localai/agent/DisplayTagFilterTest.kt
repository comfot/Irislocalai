package com.iris.localai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTagFilterTest {

    @Test
    fun stripsMaintextAndDropsStatusBlock() {
        val filter = DisplayTagFilter()
        val input = """
            <maintext>
            动作跑起来了，角色动了一下。
            </maintext>
            <Status_block>
            『日期：11月9日』
            </Status_block>
        """.trimIndent()
        val output = filter.feed(input) + filter.flush()
        assertEquals("动作跑起来了，角色动了一下。", output.trim())
    }

    @Test
    fun handlesTagsSplitAcrossChunks() {
        val filter = DisplayTagFilter()
        assertTrue(filter.feed("<main").isEmpty())
        val middle = filter.feed("text>正文</main")
        assertEquals("正文", middle)
        assertTrue(filter.feed("text><Status_bl").isEmpty())
        assertTrue(filter.feed("ock>状态</Status_block>尾巴").trim() == "尾巴")
    }

    @Test
    fun dropsUnclosedStatusBlockOnFlush() {
        val filter = DisplayTagFilter()
        assertEquals("前面", filter.feed("前面<Status_block>状态"))
        assertEquals("", filter.flush())
    }

    @Test
    fun keepsNormalTextAndAngleBrackets() {
        val filter = DisplayTagFilter()
        val text = "x < y 时输出，不要过滤"
        assertEquals(text, filter.feed(text) + filter.flush())
    }
}
