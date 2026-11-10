package com.iris.localai.memory

import org.junit.Assert.assertTrue
import org.junit.Test

class HashEmbeddingTest {

    @Test
    fun similarTextHasHigherCosineThanUnrelatedText() {
        val query = HashEmbedding.embed("上次说的那只猫")
        val related = HashEmbedding.embed("我养了一只猫，它喜欢晒太阳")
        val unrelated = HashEmbedding.embed("今天编译安卓项目失败")

        val relatedScore = HashEmbedding.cosine(query, related)
        val unrelatedScore = HashEmbedding.cosine(query, unrelated)

        assertTrue("相关文本得分应高于无关文本", relatedScore > unrelatedScore)
    }

    @Test
    fun vectorIsNormalized() {
        val vector = HashEmbedding.embed("记忆检索与向量召回")
        val length = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() })
        assertTrue(length > 0.99 && length < 1.01)
    }
}
