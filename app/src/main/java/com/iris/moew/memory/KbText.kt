package com.iris.moew.memory

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import kotlin.math.ln

/**
 * 知识检索的纯函数工具集，对齐 IRIDIS 2.7.2 的做法：
 * 分块（500 字/80 重叠）、CJK 重叠 bigram 分词、BM25 + 归一化、
 * 查询清洗（停用词/口语剥离/确认词跳过）、RRF 融合与整词强命中保底。
 */
object KbText {

    // ---------- 编码识别 ----------

    private val GB_COMMON = (
        "的一是了我不人在他有这上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世什两次使身者被高已亲其进此话常与活正感"
        ).toSet()

    fun detectEncoding(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        val probe = bytes.takeBytes(2048)
        if (probe.size >= 4 && looksLikeUtf16(probe)) {
            val zeroOdd = probe.countIndexedBy { i, b -> i % 2 == 1 && b == 0.toByte() }
            val zeroEven = probe.countIndexedBy { i, b -> i % 2 == 0 && b == 0.toByte() }
            return if (zeroEven > zeroOdd) Charsets.UTF_16LE else Charsets.UTF_16BE
        }
        if (runsAsUtf8(probe)) return Charsets.UTF_8
        val gb = runCatching { probe.toString(Charset.forName("GB18030")) }.getOrDefault("")
        val big5 = runCatching { probe.toString(Charset.forName("Big5")) }.getOrDefault("")
        val gbScore = commonHanRatio(gb)
        val big5Score = commonHanRatio(big5)
        return if (big5Score > gbScore && big5Score > 0.15f) {
            Charset.forName("Big5")
        } else {
            Charset.forName("GB18030")
        }
    }

    private fun looksLikeUtf16(probe: ByteArray): Boolean {
        var zeroOdd = 0
        var zeroEven = 0
        probe.forEachIndexed { i, b ->
            if (b == 0.toByte()) {
                if (i % 2 == 1) zeroOdd++ else zeroEven++
            }
        }
        val printable = probe.any { it.toInt() in 0x21..0x7E }
        return zeroOdd + zeroEven > probe.size / 4 && printable
    }

    private fun runsAsUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    private fun commonHanRatio(text: String): Float {
        val han = text.filter { it.code in 0x3400..0x9FFF }
        if (han.isEmpty()) return 0f
        return han.count { it in GB_COMMON }.toFloat() / han.length
    }

    private fun ByteArray.takeBytes(limit: Int): ByteArray =
        if (size <= limit) this else copyOf(limit)

    private inline fun ByteArray.countIndexedBy(predicate: (Int, Byte) -> Boolean): Int {
        var count = 0
        for (i in indices) if (predicate(i, this[i])) count++
        return count
    }

    // ---------- 分块 ----------

    /** 500 字/80 重叠；断点优先 段落 → 句读 → 空格，找不到才硬切。 */
    fun chunk(text: String, size: Int = 500, overlap: Int = 80): List<String> {
        val clean = text.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        if (clean.length <= size) {
            return if (clean.isEmpty()) emptyList() else listOf(clean)
        }
        val step = (size - overlap).coerceAtLeast(1)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = (start + size).coerceAtMost(clean.length)
            if (end < clean.length) {
                val window = clean.substring(start, end)
                val paragraph = window.lastIndexOf("。")
                val sentence = maxOf(window.lastIndexOf("；"), window.lastIndexOf("．"), window.lastIndexOf("！"), window.lastIndexOf("？"))
                val comma = window.lastIndexOf("，")
                val space = window.lastIndexOf(' ')
                val cut = listOf(paragraph, sentence, comma, space).maxOrNull() ?: -1
                if (cut >= size / 2) end = start + cut + 1
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) chunks += piece
            if (end >= clean.length) break
            start += step
        }
        return chunks
    }

    // ---------- 分词 ----------

    /** 拉丁按词，CJK 相邻重叠 bigram（单字成 unigram）。 */
    fun tokenize(text: String): List<String> {
        val lower = text.lowercase()
        val tokens = mutableListOf<String>()
        val word = StringBuilder()
        fun flushWord() {
            if (word.isNotEmpty()) {
                tokens += word.toString()
                word.clear()
            }
        }
        lower.forEach { ch ->
            when {
                ch.code in 0x4E00..0x9FFF -> {
                    flushWord()
                    tokens += ch.toString()
                }
                ch.isLetterOrDigit() -> word.append(ch)
                else -> flushWord()
            }
        }
        flushWord()
        if (tokens.size >= 2) {
            val grams = tokens.zipWithNext { a, b -> a + b }
            return (tokens + grams).distinct()
        }
        return tokens.distinct()
    }

    // ---------- 查询清洗 ----------

    private val STOP = setOf(
        "什么", "怎么", "怎样", "如何", "为什么", "请问", "帮我", "一下", "关于", "知识",
        "资料", "文档", "告诉", "知道", "看看", "介绍", "说明", "解释", "意思", "描述",
        "the", "and", "for", "with", "that", "this", "what", "how", "why", "about",
    )

    private val ACK = Regex(
        "^(好的?|嗯+|哦+|继续|知道了|明白了|谢谢|多谢|ok|okay|yes|no|sure|继续吧|请继续)[。!！.\\s]*$",
        RegexOption.IGNORE_CASE,
    )

    /** 去掉「知识库里/关于/怎么描述」一类口语说法，再分词并去停用词。 */
    fun effectiveTokens(query: String): List<String> {
        var q = query.trim()
        q = q.replace(Regex("在知识库(里|中|里找|中找)?"), " ")
        q = q.replace(Regex("知识库(里|中)?(有|关于|说|讲)?"), " ")
        q = q.replace(Regex("^(帮我|请|麻烦)?(查一下|查查|找一下|看看)"), " ")
        q = q.replace(Regex("关于|是怎么(说|写|讲)的|如何(描述|介绍)"), " ")
        return tokenize(q).filter { it !in STOP && it.length >= 2 || it.length == 1 && it.first().code in 0x4E00..0x9FFF }
    }

    /** 整条消息是确认词时跳过检索，避免「好的」「继续」召回无关内容。 */
    fun isPureAck(query: String): Boolean = ACK.matches(query.trim())

    /** 整词原样命中（强命中保底的判定之一）。 */
    fun containsWholeWord(haystack: String, needle: String): Boolean = when {
        needle.isEmpty() -> false
        needle.all { it.code in 0x4E00..0x9FFF } -> haystack.contains(needle)
        else -> Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(needle)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            .containsMatchIn(haystack)
    }

    // ---------- BM25 ----------

    /** BM25 索引：手机端语料规模（几千片段）用 Map 词表即可，不引入 Int 编码数组。 */
    class Bm25(docs: List<List<String>>) {
        private val docFreq = HashMap<String, Int>()
        private val termFreq: List<Map<String, Int>>
        private val docLen: IntArray
        private val avgLen: Float

        init {
            termFreq = docs.map { tokens ->
                tokens.groupingBy { it }.eachCount().also { counts ->
                    counts.keys.forEach { token -> docFreq[token] = (docFreq[token] ?: 0) + 1 }
                }
            }
            docLen = IntArray(docs.size) { docs[it].size }
            avgLen = if (docs.isEmpty()) 1f else docs.sumOf { it.size }.toFloat() / docs.size
        }

        /** 归一化分（除以本查询最大分）；覆盖度 = 命中不同查询词 / 查询词总数。 */
        fun score(query: List<String>, k1: Float = 1.2f, b: Float = 0.75f): FloatArray {
            val n = termFreq.size
            if (n == 0 || query.isEmpty()) return FloatArray(n)
            val raw = FloatArray(n)
            var max = 0f
            for (docIndex in 0 until n) {
                var sum = 0f
                val len = docLen[docIndex].coerceAtLeast(1)
                for (token in query) {
                    val tf = termFreq[docIndex][token]?.toFloat() ?: continue
                    val df = (docFreq[token] ?: 0).toFloat()
                    if (df <= 0f) continue
                    val idf = ln(1f + (n - df + 0.5f) / (df + 0.5f))
                    sum += (idf * (tf * (k1 + 1f)) / (tf + k1 * (1f - b + b * len / avgLen))).toFloat()
                }
                raw[docIndex] = sum
                if (sum > max) max = sum
            }
            if (max <= 0f) return raw
            for (i in raw.indices) raw[i] /= max
            return raw
        }

        /** 命中的不同查询词占比，供强命中判定。 */
        fun coverage(query: List<String>): FloatArray {
            val n = termFreq.size
            if (n == 0 || query.isEmpty()) return FloatArray(n)
            val distinct = query.distinct()
            val result = FloatArray(n)
            for (docIndex in 0 until n) {
                val tf = termFreq[docIndex]
                result[docIndex] = distinct.count { tf.containsKey(it) }.toFloat() / distinct.size
            }
            return result
        }
    }

    // ---------- RRF ----------

    /** Σ 1/(k+rank)；各 ranking 是按分数降序的文档下标列表。 */
    fun rrf(rankings: List<List<Int>>, k: Int = 60): Map<Int, Float> {
        val fused = HashMap<Int, Float>()
        rankings.forEach { ranking ->
            ranking.forEachIndexed { index, docIndex ->
                fused[docIndex] = (fused[docIndex] ?: 0f) + 1f / (k + index + 1)
            }
        }
        return fused
    }
}
