package com.iris.localai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTagParserTest {

    @Test
    fun parsesTagInOneChunk() {
        val parser = PerformanceTagParser()
        val feed = parser.feed("[emo:happy:0.8]你好")
        assertEquals("你好", feed.text)
        val tag = feed.tags.single() as PerformanceTag.Emotion
        assertEquals("happy", tag.name)
        assertEquals(0.8f, tag.intensity, 0.001f)
    }

    @Test
    fun buffersTagAcrossChunks() {
        val parser = PerformanceTagParser()
        assertTrue(parser.feed("[em").text.isEmpty())
        val feed = parser.feed("o:sad]哇")
        assertEquals("哇", feed.text)
        assertEquals("sad", (feed.tags.single() as PerformanceTag.Emotion).name)
    }

    @Test
    fun keepsUnknownBracketsAsText() {
        val parser = PerformanceTagParser()
        val feed = parser.feed("[笑]你好")
        assertEquals("[笑]你好", feed.text)
        assertTrue(feed.tags.isEmpty())
    }

    @Test
    fun flushesUnclosedTagAsText() {
        val parser = PerformanceTagParser()
        assertTrue(parser.feed("a[").text == "a")
        assertEquals("[笑]", parser.feed("笑]").text)
        val flushed = parser.flush()
        assertTrue(flushed.text.isEmpty())
    }

    @Test
    fun parsesDanceCameraAndMotion() {
        val parser = PerformanceTagParser()
        val feed = parser.feed("[dance:][cam:cam-arc][motion:Group:2]")
        assertEquals("", feed.text)
        assertEquals(3, feed.tags.size)
        assertEquals("", (feed.tags[0] as PerformanceTag.Dance).name)
        assertEquals("cam-arc", (feed.tags[1] as PerformanceTag.Camera).name)
        val motion = feed.tags[2] as PerformanceTag.Motion
        assertEquals("Group", motion.group)
        assertEquals(2, motion.index)
    }
}
