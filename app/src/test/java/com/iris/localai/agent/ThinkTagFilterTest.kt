package com.iris.localai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkTagFilterTest {

    @Test
    fun stripsClosedThinkBlock() {
        val filter = ThinkTagFilter()
        val feed = filter.feed("<think>分析一下</think>你好")
        assertEquals("你好", feed.text)
        assertEquals("分析一下", feed.reasoning)
        assertTrue(feed.sawThink)
    }

    @Test
    fun handlesTagsSplitAcrossChunks() {
        val filter = ThinkTagFilter()
        assertTrue(filter.feed("<th").text.isEmpty())
        val middle = filter.feed("ink>内部</thi")
        assertEquals("内部", middle.reasoning)
        assertTrue(middle.text.isEmpty())
        val end = filter.feed("nk>正文")
        assertEquals("正文", end.text)
    }

    @Test
    fun dropsUnclosedThinkBlock() {
        val filter = ThinkTagFilter()
        val feed = filter.feed("<think>只有思考")
        assertTrue(feed.text.isEmpty())
        assertEquals("只有思考", feed.reasoning)
        val flush = filter.flush()
        assertTrue(flush.reasoning.isEmpty())
        assertTrue(flush.text.isEmpty())
        assertTrue(filter.hasThought())
    }

    @Test
    fun keepsNormalAngleBrackets() {
        val filter = ThinkTagFilter()
        val feed = filter.feed("x < y 时输出")
        assertEquals("x < y 时输出", feed.text)
        assertFalse(feed.sawThink)
    }

    @Test
    fun handlesMultipleBlocks() {
        val filter = ThinkTagFilter()
        val feed = filter.feed("<think>a</think>中间<think>b</think>结尾")
        assertEquals("中间结尾", feed.text)
        assertEquals("ab", feed.reasoning)
    }
}
