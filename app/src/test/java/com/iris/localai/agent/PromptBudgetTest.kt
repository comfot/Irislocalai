package com.iris.localai.agent

import com.iris.localai.engine.PromptMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptBudgetTest {

    @Test
    fun `estimates cjk heavier than ascii`() {
        assertEquals(4, estimatePromptTokens("你好世界"))
        assertEquals(1, estimatePromptTokens("abcd"))
        assertEquals(0, estimatePromptTokens(""))
    }

    @Test
    fun `keeps newest messages within budget`() {
        val history = listOf(
            PromptMessage(PromptMessage.USER, "aaaa"),
            PromptMessage(PromptMessage.ASSISTANT, "bbbb"),
            PromptMessage(PromptMessage.USER, "cccc"),
        )

        val trimmed = trimPromptHistory(history, 9)

        assertEquals(1, trimmed.size)
        assertEquals("cccc", trimmed.first().content)
    }

    @Test
    fun `keeps preceding user message for assistant head`() {
        val history = listOf(
            PromptMessage(PromptMessage.USER, "x"),
            PromptMessage(PromptMessage.ASSISTANT, "y"),
        )

        val trimmed = trimPromptHistory(history, 9)

        assertEquals(listOf("x", "y"), trimmed.map { it.content })
    }

    @Test
    fun `returns empty for empty history or budget`() {
        assertEquals(0, trimPromptHistory(emptyList(), 100).size)
        assertEquals(0, trimPromptHistory(listOf(PromptMessage(PromptMessage.USER, "x")), 0).size)
    }
}
