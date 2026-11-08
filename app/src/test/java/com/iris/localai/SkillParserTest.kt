package com.iris.localai

import com.iris.localai.skill.SkillParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    @Test
    fun parsesFrontmatterAndBody() {
        val skill = SkillParser.parse(
            """
            ---
            name: 会议纪要
            description: 整理会议内容
            triggers: 开会,会议
            alwaysOn: true
            version: 1.2
            ---
            # 步骤
            先读原文，再输出纪要。
            """.trimIndent(),
        )

        assertEquals("会议纪要", skill.name)
        assertEquals("整理会议内容", skill.description)
        assertEquals(listOf("开会", "会议"), skill.triggers)
        assertTrue(skill.alwaysOn)
        assertTrue(skill.instructions.contains("先读原文"))
        assertEquals("1.2", skill.version)
    }
}
