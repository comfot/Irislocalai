package com.iris.moew.ui.chat

/**
 * LaTeX 公式转可读 Unicode 文本：希腊字母、常见运算符、分式、根号、上下标。
 * 不做完整排版，只保证模型输出的常见公式不再以原始 LaTeX 显示。
 */
object LaTeXText {

    private val GREEK = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
        "rho" to "ρ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ",
        "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    )

    private val SYMBOLS = mapOf(
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀",
        "exists" to "∃", "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "cup" to "∪", "cap" to "∩", "emptyset" to "∅",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
        "Leftarrow" to "⇐", "leftrightarrow" to "↔", "mapsto" to "↦",
        "sum" to "Σ", "prod" to "∏", "coprod" to "∐", "int" to "∫",
        "oint" to "∮", "sqrt" to "√", "angle" to "∠", "perp" to "⊥",
        "parallel" to "∥", "cdots" to "⋯", "ldots" to "…", "dots" to "…",
        "vdots" to "⋮", "ddots" to "⋱", "prime" to "′", "circ" to "∘",
        "star" to "⋆", "oplus" to "⊕", "otimes" to "⊗", "odot" to "⊙",
        "neg" to "¬", "land" to "∧", "lor" to "∨", "degree" to "°",
    )

    private val FUNCTIONS = listOf(
        "sinh", "cosh", "tanh", "sin", "cos", "tan", "log", "ln", "exp",
        "lim", "max", "min", "sup", "inf", "gcd", "lcm", "det", "dim", "deg", "arg",
    )

    private val SUPERSCRIPT = mapOf(
        '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴",
        '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹",
        '+' to "⁺", '-' to "⁻", '=' to "⁼", '(' to "⁽", ')' to "⁾",
        'a' to "ᵃ", 'b' to "ᵇ", 'c' to "ᶜ", 'd' to "ᵈ", 'e' to "ᵉ",
        'f' to "ᶠ", 'g' to "ᵍ", 'h' to "ʰ", 'i' to "ⁱ", 'j' to "ʲ",
        'k' to "ᵏ", 'l' to "ˡ", 'm' to "ᵐ", 'n' to "ⁿ", 'o' to "ᵒ",
        'p' to "ᵖ", 'r' to "ʳ", 's' to "ˢ", 't' to "ᵗ", 'u' to "ᵘ",
        'v' to "ᵛ", 'w' to "ʷ", 'x' to "ˣ", 'y' to "ʸ", 'z' to "ᶻ",
    )

    private val SUBSCRIPT = mapOf(
        '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄",
        '5' to "₅", '6' to "₆", '7' to "₇", '8' to "₈", '9' to "₉",
        '+' to "₊", '-' to "₋", '=' to "₌", '(' to "₍", ')' to "₎",
        'a' to "ₐ", 'e' to "ₑ", 'h' to "ₕ", 'i' to "ᵢ", 'j' to "ⱼ",
        'k' to "ₖ", 'l' to "ₗ", 'm' to "ₘ", 'n' to "ₙ", 'o' to "ₒ",
        'p' to "ₚ", 'r' to "ᵣ", 's' to "ₛ", 't' to "ₜ", 'u' to "ᵤ",
        'v' to "ᵥ", 'x' to "ₓ",
    )

    fun render(input: String): String {
        var text = input
        text = text.replace(Regex("\\\\left|\\\\right"), "")
        text = text.replace(Regex("\\\\[(),;.!\\s]"), " ")
        text = text.replace(Regex("\\\\displaystyle|\\\\textstyle|\\\\limits"), "")
        // \frac{a}{b} / \dfrac{a}{b} → (a)/(b)
        text = text.replace(Regex("\\\\[dt]?frac\\{([^{}]+)}\\{([^{}]+)}")) { match ->
            val numerator = match.groupValues[1].trim()
            val denominator = match.groupValues[2].trim()
            val wrapNum = if (numerator.length > 1) "($numerator)" else numerator
            val wrapDen = if (denominator.length > 1) "($denominator)" else denominator
            "$wrapNum⁄$wrapDen"
        }
        // \sqrt[n]{x} 与 \sqrt{x}
        text = text.replace(Regex("\\\\sqrt\\[([^{}]+)]\\{([^{}]+)}")) { match ->
            "(${match.groupValues[2].trim()})^(1⁄${match.groupValues[1].trim()})"
        }
        text = text.replace(Regex("\\\\sqrt\\{([^{}]+)}")) { match -> "√(${match.groupValues[1].trim()})" }
        // \gcd(a,b) 等函数名
        FUNCTIONS.forEach { name ->
            text = text.replace(Regex("\\\\$name\\b"), name)
        }
        // 上标 ^{...} 或 ^x
        text = Regex("\\^\\{([^{}]+)}").replace(text) { match -> superscript(match.groupValues[1]) }
        text = Regex("\\^([0-9a-zA-Z+\\-()])").replace(text) { match -> superscript(match.groupValues[1]) }
        // 下标 _{...} 或 _x
        text = Regex("_\\{([^{}]+)}").replace(text) { match -> subscript(match.groupValues[1]) }
        text = Regex("_([0-9a-zA-Z+\\-()])").replace(text) { match -> subscript(match.groupValues[1]) }
        // 符号与希腊字母（长名优先，避免 \epsilon 被 \e 截断——按词边界匹配）
        val combined = SYMBOLS + GREEK
        combined.keys.sortedByDescending { it.length }.forEach { name ->
            text = text.replace(Regex("\\\\$name\\b"), combined.getValue(name))
        }
        text = text.replace(Regex("\\\\\\\\(\\s*)"), " , ")
        return text.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun superscript(body: String): String =
        body.map { SUPERSCRIPT[it] ?: it }.joinToString("")

    private fun subscript(body: String): String =
        body.map { SUBSCRIPT[it] ?: it }.joinToString("")

    /** 行内分段：$…$ 与 \(…\) 作为公式，其余为普通文本。 */
    data class Segment(val text: String, val isMath: Boolean)

    fun splitInline(text: String): List<Segment> {
        if (!text.contains('$') && !text.contains("\\(")) {
            return listOf(Segment(text, isMath = false))
        }
        val segments = mutableListOf<Segment>()
        val buffer = StringBuilder()
        var index = 0
        while (index < text.length) {
            val rest = text.substring(index)
            val dollar = rest.startsWith("$$")
            val singleDollar = rest.startsWith('$')
            val paren = rest.startsWith("\\(")
            val mathEnd: Int? = when {
                dollar -> rest.indexOf("$$", 2)
                singleDollar -> rest.indexOf('$', 1)
                paren -> rest.indexOf("\\)", 2)
                else -> null
            }
            if ((dollar || singleDollar || paren) && mathEnd != null && mathEnd > 1) {
                if (buffer.isNotEmpty()) {
                    segments += Segment(buffer.toString(), isMath = false)
                    buffer.clear()
                }
                val body = rest.substring(if (dollar) 2 else if (paren) 2 else 1, mathEnd)
                segments += Segment(render(body), isMath = true)
                index += mathEnd + if (dollar || paren) 2 else 1
            } else {
                buffer.append(text[index])
                index++
            }
        }
        if (buffer.isNotEmpty()) segments += Segment(buffer.toString(), isMath = false)
        return segments
    }

    /** 疑似数学的行内代码（含强数学特征且不像代码）转公式渲染。 */
    fun looksMathy(code: String): Boolean {
        if (code.contains("<") || code.contains("->") || code.contains("::")) return false
        val features = listOf("\\frac", "\\sqrt", "\\sum", "\\int", "^{", "^", "_{", "\\times", "\\pi")
        return features.any { code.contains(it) } && code.length <= 120
    }
}
