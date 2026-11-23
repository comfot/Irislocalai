package com.iris.moew.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkMarkdownTest {

    private fun inline(text: String) = buildAnnotatedString {
        appendInline(
            text = text,
            base = SpanStyle(),
            code = SpanStyle(),
            accent = Color(0xFF0066CC),
            math = SpanStyle(),
        )
    }

    @OptIn(ExperimentalTextApi::class)
    private fun urls(annotated: androidx.compose.ui.text.AnnotatedString) =
        annotated.getLinkAnnotations(0, annotated.length).map { (it.item as LinkAnnotation.Url).url }

    @Test
    fun `blocks cover common shapes`() {
        val blocks = parseWorkMarkdown(
            """
            # 标题
            普通段落。
            - 项目甲
            - 项目乙
            1. 第一
            2. 第二
            > 引用一行
            ***
            ```kotlin
            val a = 1
            ```
            | 左 | 中 |
            | :-- | :-: |
            | a | b |
            """.trimIndent(),
        )

        assertEquals(8, blocks.size)
        assertTrue(blocks[0] is WorkBlock.Heading)
        assertTrue(blocks[1] is WorkBlock.Paragraph)
        assertEquals(listOf("项目甲", "项目乙"), (blocks[2] as WorkBlock.Bullets).items)
        assertEquals(listOf("第一", "第二"), (blocks[3] as WorkBlock.Numbers).items)
        assertTrue(blocks[4] is WorkBlock.Quote)
        assertEquals(WorkBlock.Divider, blocks[5])
        assertEquals("kotlin", (blocks[6] as WorkBlock.Code).language)
        val table = blocks[7] as WorkBlock.Table
        assertEquals(listOf("左", "中"), table.headers)
        assertEquals(WorkAlign.CENTER, table.alignments[1])
        assertEquals(listOf(listOf("a", "b")), table.rows)
    }

    @Test
    fun `divider requires three same chars`() {
        assertEquals(WorkBlock.Divider, parseWorkMarkdown("* * *\n正文")[0])
        assertEquals(WorkBlock.Divider, parseWorkMarkdown("---\n正文")[0])
        val dashFace = parseWorkMarkdown("-_-\n正文")
        assertTrue(dashFace[0] is WorkBlock.Paragraph)
    }

    @Test
    fun `escapes render literally`() {
        assertEquals("*不是斜体*", inline("\\*不是斜体\\*").text)
        assertEquals("` 不是代码", inline("\\` 不是代码").text)
    }

    @Test
    fun `bold italic combo has no stray marker`() {
        assertEquals("重点", inline("***重点***").text)
    }

    @Test
    fun `image renders clickable label`() {
        val annotated = inline("![架构](https://a/b.png)")
        assertEquals("图片：架构", annotated.text)
        assertEquals(listOf("https://a/b.png"), urls(annotated))
    }

    @Test
    fun `link keeps balanced parens and drops title`() {
        val annotated = inline("[维基](https://en.wikipedia.org/wiki/A_(b) \"T\")")
        assertEquals("维基", annotated.text)
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/A_(b)"),
            urls(annotated),
        )
    }

    @Test
    fun `non http scheme stays plain text`() {
        val annotated = inline("[点我](javascript:alert(1))")
        assertEquals("[点我](javascript:alert(1))", annotated.text)
        assertTrue(urls(annotated).isEmpty())
    }

    @Test
    fun `angle and bare autolink become links`() {
        val angle = inline("<https://a.b/c>")
        assertEquals("https://a.b/c", angle.text)
        assertEquals(listOf("https://a.b/c"), urls(angle))

        val bare = inline("看 https://a.b/c。好")
        assertEquals("看 https://a.b/c。好", bare.text)
        assertEquals(listOf("https://a.b/c"), urls(bare))

        val www = inline("到 www.example.com/x 了吗")
        assertEquals("https://www.example.com/x", urls(www).single())
    }

    @Test
    fun `math blocks parse from dollar and bracket fences`() {
        val multiline = parseWorkMarkdown("$$\nx^2+y^2\n$$\n后文")
        assertEquals(listOf<WorkBlock>(WorkBlock.Math("x^2+y^2"), WorkBlock.Paragraph("后文")), multiline)

        val single = inline("前文 \$\$x^2\$\$ 后文")
        assertEquals("前文 x^2 后文", single.text)

        val bracket = parseWorkMarkdown("\\[\n\\frac{a}{b}\n\\]")
        assertEquals(listOf<WorkBlock>(WorkBlock.Math("\\frac{a}{b}")), bracket)
    }

    @Test
    fun `inline math is protected from markdown markers`() {
        assertEquals("a_1+b", inline("\$a_1+b\$").text)
        assertEquals("a+b", inline("\\(a+b\\)").text)
        assertEquals("\\frac{a}{b}", inline("$\\frac{a}{b}$").text)
    }

    @Test
    fun `dollar edge cases stay literal`() {
        assertEquals("$5", inline("\\$5").text)
        assertEquals("总价 $5 与 $6 元", inline("总价 $5 与 $6 元").text)
        assertEquals("总价 $5", inline("总价 $5").text)
    }
}
