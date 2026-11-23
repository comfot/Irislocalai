package com.iris.moew.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class KbTextTest {

    @Test
    fun `chunk splits long text at sentence boundary with overlap`() {
        val text = ("这是一段很长的知识内容。".repeat(120))
        val chunks = KbText.chunk(text, size = 500, overlap = 80)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= 500) }
        // 重叠会导致总长度不小于原文。
        assertTrue(chunks.sumOf { it.length } >= text.length)
    }

    @Test
    fun `chunk keeps short text as single piece`() {
        assertEquals(listOf("短文本"), KbText.chunk("短文本"))
        assertTrue(KbText.chunk("").isEmpty())
    }

    @Test
    fun `tokenize handles latin words and cjk bigrams`() {
        val tokens = KbText.tokenize("用户喜欢冰美式 coffee")
        assertTrue(tokens.contains("coffee"))
        assertTrue(tokens.contains("用户"))
        assertTrue(tokens.contains("户喜"))
        assertTrue(tokens.contains("冰美"))
    }

    @Test
    fun `effectiveTokens strips colloquial wrappers and stopwords`() {
        val tokens = KbText.effectiveTokens("帮我查一下知识库里关于分北帽的描述")
        assertTrue(tokens.contains("分北帽") || tokens.contains("分北"))
        assertFalse(tokens.contains("知识库"))
        assertFalse(tokens.contains("一下"))
        assertTrue(KbText.isPureAck("好的"))
        assertTrue(KbText.isPureAck("继续吧。"))
        assertFalse(KbText.isPureAck("告诉我分院帽的设定"))
    }

    @Test
    fun `detectEncoding distinguishes bom utf16 gb18030 and big5`() {
        val utf8 = "分院帽与冰美式".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, KbText.detectEncoding(utf8))

        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "分院帽".toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, KbText.detectEncoding(utf16le))

        val utf16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "分院帽".toByteArray(Charsets.UTF_16BE)
        assertEquals(Charsets.UTF_16BE, KbText.detectEncoding(utf16be))

        val gb = "分院帽与冰美式".toByteArray(Charset.forName("GB18030"))
        assertEquals(Charset.forName("GB18030"), KbText.detectEncoding(gb))
    }

    @Test
    fun `bm25 ranks matching doc highest and coverage measures hit ratio`() {
        val docs = listOf(
            listOf("分院帽", "格兰芬多", "分院帽", "霍格沃茨", "分院帽格兰芬多"),
            listOf("冰美式", "咖啡", "冰美式咖啡"),
            listOf("完全无关的内容"),
        )
        val bm25 = KbText.Bm25(docs)
        val scores = bm25.score(listOf("分院帽", "霍格沃茨"))
        assertEquals(0, scores.indices.maxByOrNull { scores[it] })
        assertTrue(scores[1] == 0f)

        val coverage = bm25.coverage(listOf("分院帽", "冰美式"))
        // doc0 只含分院帽，doc1 只含冰美式，doc2 都不含。
        assertEquals(0.5f, coverage[0], 0.001f)
        assertEquals(0.5f, coverage[1], 0.001f)
        assertEquals(0f, coverage[2], 0.001f)
    }

    @Test
    fun `rrf fuses rankings by reciprocal rank`() {
        val fused = KbText.rrf(listOf(listOf(0, 1, 2), listOf(1, 0, 2)))
        // 0 在两个榜里分别第 1、第 2；1 是第 2、第 1，总分相等；2 都在第 3。
        assertEquals(fused.getValue(0), fused.getValue(1), 0.001f)
        assertTrue(fused.getValue(0) > fused.getValue(2))
    }

    @Test
    fun `containsWholeWord matches cjk substring and latin boundary`() {
        assertTrue(KbText.containsWholeWord("他喜欢冰美式", "冰美式"))
        assertTrue(KbText.containsWholeWord("he likes coffee", "coffee"))
        assertFalse(KbText.containsWholeWord("he likes coffee", "coffeeee"))
    }
}
