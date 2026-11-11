package com.iris.localai.browser

import com.iris.localai.integration.BrowserConfig
import java.net.URI

/** 浏览器域名策略：只允许 http(s)，阻断名单优先，允许名单非空时其余域名默认拒绝。 */
object BrowserPolicy {

    fun check(url: String, config: BrowserConfig): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return "仅允许 http/https：$url"
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()
            ?.takeIf { it.isNotBlank() } ?: return "URL 缺少有效域名：$url"
        val blocked = rules(config.blockDomains)
        if (blocked.any { matches(host, it) }) return "域名在阻断名单：$host"
        val allowed = rules(config.allowDomains)
        if (allowed.isNotEmpty() && allowed.none { matches(host, it) }) {
            return "域名不在允许名单：$host"
        }
        return null
    }

    private fun rules(raw: String): List<String> = raw
        .split(',', '\n', ' ')
        .map { it.trim().lowercase().removePrefix("*.").removePrefix(".") }
        .filter { it.isNotBlank() }

    private fun matches(host: String, rule: String): Boolean =
        host == rule || host.endsWith(".$rule")
}
