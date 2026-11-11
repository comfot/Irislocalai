package com.iris.localai.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.WebView
import com.iris.localai.integration.BrowserConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/** AI 浏览器的 WebView 控制器：页面在 BrowserActivity 中显示，工具通过 JS 读取/点击/输入。 */
class BrowserController(
    private val context: Context,
    private val configProvider: () -> BrowserConfig = { BrowserConfig() },
) {

    private var viewRef: WeakReference<WebView>? = null
    private var lastSnapshot: String? = null
    var lastUrl: String = ""
        private set

    fun attach(view: WebView) {
        viewRef = WeakReference(view)
        lastUrl = view.url.orEmpty()
    }

    fun detach(view: WebView) {
        if (viewRef?.get() === view) viewRef = null
    }

    fun noteUrl(url: String?) {
        if (!url.isNullOrBlank()) lastUrl = url
    }

    fun open(url: String) {
        val normalized = normalize(url)
        if (!configProvider().enabled) error("AI 浏览器已在设置中关闭")
        BrowserPolicy.check(normalized, configProvider())?.let { error(it) }
        lastSnapshot = null
        context.startActivity(
            Intent(context, BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BrowserActivity.EXTRA_URL, normalized),
        )
    }

    suspend fun openAndWait(url: String): String {
        open(url)
        delay(900)
        return lastUrl.ifBlank { url }
    }

    suspend fun read(): String {
        val raw = evaluate(JS_READ) ?: error("AI 浏览器尚未打开，请先调用 browser_open")
        val text = unquote(raw)
        return text.ifBlank { "页面没有可读文本" }
    }

    suspend fun click(text: String): String {
        val raw = evaluate(JS_CLICK.replace("__TEXT__", JSONObject.quote(text))) ?: error("AI 浏览器尚未打开")
        return if (unquote(raw).contains("notfound")) "未找到可点击文字：$text" else "已点击：$text"
    }

    suspend fun input(target: String, text: String): String {
        val script = JS_INPUT
            .replace("__TARGET__", JSONObject.quote(target))
            .replace("__TEXT__", JSONObject.quote(text))
        val raw = evaluate(script) ?: error("AI 浏览器尚未打开")
        return if (unquote(raw).contains("notfound")) "未找到输入框：$target" else "已输入"
    }

    suspend fun snapshot(): String {
        val raw = evaluate(JS_SNAPSHOT) ?: error("AI 浏览器尚未打开，请先调用 browser_open")
        val text = unquote(raw)
        val page = text.ifBlank { "页面没有可读文本" }
        val previous = lastSnapshot
        lastSnapshot = page
        return if (previous == page) "$page\nHINT 页面与上次快照相同" else page
    }

    suspend fun clickIndex(index: Int): String {
        val raw = evaluate(JS_CLICK_INDEX.replace("__INDEX__", index.coerceAtLeast(0).toString()))
            ?: error("AI 浏览器尚未打开")
        return if (unquote(raw).contains("notfound")) "索引 $index 已失效，请重新 snapshot" else "已点击索引 $index"
    }

    suspend fun typeIndex(index: Int, text: String, submit: Boolean): String {
        val script = JS_TYPE_INDEX
            .replace("__INDEX__", index.coerceAtLeast(0).toString())
            .replace("__TEXT__", JSONObject.quote(text))
            .replace("__SUBMIT__", if (submit) "true" else "false")
        val raw = evaluate(script) ?: error("AI 浏览器尚未打开")
        return if (unquote(raw).contains("notfound")) "索引 $index 不是可输入元素" else "已输入（值长度 ${text.length}）"
    }

    suspend fun scroll(dy: Int): String {
        val script = JS_SCROLL.replace("__DY__", dy.toString())
        val raw = evaluate(script) ?: error("AI 浏览器尚未打开")
        return "已滚动，当前 scrollY=${unquote(raw)}"
    }

    suspend fun waitForText(text: String, timeoutMs: Int): String {
        val query = text.trim()
        if (query.isBlank()) error("等待文本不能为空")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(300, 30_000)
        while (System.currentTimeMillis() < deadline) {
            runCatching { snapshot() }.getOrNull()?.let { page ->
                if (page.contains(query, ignoreCase = true)) return "已出现：$text"
            }
            delay(600)
        }
        error("等待超时：未出现「$text」")
    }

    suspend fun navigate(action: String): String {
        val handled = withContext(Dispatchers.Main) {
            val view = viewRef?.get() ?: return@withContext false
            when (action.lowercase()) {
                "back" -> if (view.canGoBack()) { view.goBack(); true } else false
                "forward" -> if (view.canGoForward()) { view.goForward(); true } else false
                "reload" -> { view.reload(); true }
                else -> false
            }
        }
        return if (handled) {
            lastSnapshot = null
            "已执行：$action"
        } else {
            "无法执行：$action"
        }
    }

    suspend fun back(): String {
        val handled = withContext(Dispatchers.Main) {
            val view = viewRef?.get() ?: return@withContext false
            if (view.canGoBack()) {
                view.goBack()
                true
            } else {
                false
            }
        }
        return if (handled) {
            lastSnapshot = null
            "已返回上一页"
        } else {
            "没有可返回的页面"
        }
    }

    suspend fun screenshot(): String {
        val config = configProvider()
        if (!config.allowScreenshot) error("截图已在设置中关闭")
        if (Regex("(pay|login|alipay|passport|auth)", RegexOption.IGNORE_CASE).containsMatchIn(lastUrl)) {
            error("敏感页面禁止截图：$lastUrl")
        }
        val passwordFields = runCatching {
            unquote(evaluate("String(document.querySelectorAll('input[type=password]').length)") ?: "0")
                .toIntOrNull() ?: 0
        }.getOrDefault(0)
        if (passwordFields > 0) error("页面含密码框，禁止截图")
        val bitmap = withContext(Dispatchers.Main) {
            val view = viewRef?.get() ?: return@withContext null
            val width = view.width.coerceAtLeast(1)
            val height = view.height.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            bmp
        } ?: error("AI 浏览器尚未打开")
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = File(dir, "browser-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    private suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val view = viewRef?.get() ?: return@withContext null
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
    }

    private fun unquote(raw: String): String =
        runCatching { JSONTokener(raw).nextValue()?.toString() ?: raw }.getOrDefault(raw)

    fun normalize(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return "https://www.bing.com"
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.contains(".") && !trimmed.contains(" ")) return "https://$trimmed"
        return "https://www.bing.com/search?q=${Uri.encode(trimmed)}"
    }

    private companion object {
        const val JS_READ = """
            (() => {
              const links = [...document.querySelectorAll('a,button,[role=button],input')]
                .slice(0, 40)
                .map((el) => (el.innerText || el.value || el.getAttribute('aria-label') || '').trim())
                .filter(Boolean);
              const body = document.body ? document.body.innerText : '';
              return [document.title, location.href, body.slice(0, 6000), '--- links ---', links.join('\n')].join('\n');
            })()
        """

        const val JS_CLICK = """
            (() => {
              const q = __TEXT__;
              const els = [...document.querySelectorAll('a,button,[role=button],input[type=submit]')];
              const el = els.find((e) => ((e.innerText || e.value || e.getAttribute('aria-label') || '').toLowerCase().includes(String(q).toLowerCase())));
              if (!el) return 'notfound';
              el.scrollIntoView({ block: 'center' });
              el.click();
              return 'ok';
            })()
        """

        const val JS_INPUT = """
            (() => {
              const target = __TARGET__;
              const text = __TEXT__;
              const els = [...document.querySelectorAll('input,textarea,[contenteditable=true]')];
              const el = els.find((e) => (e.getAttribute('placeholder') || e.getAttribute('aria-label') || e.name || '').includes(target)) || els[0];
              if (!el) return 'notfound';
              el.focus();
              if ('value' in el) { el.value = text; } else { el.innerText = text; }
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              return 'ok';
            })()
        """

        const val JS_SNAPSHOT = """
            (() => {
              const els = [...document.querySelectorAll('a[href],button,input,textarea,select,[role=button],[role=link],[role=tab],[onclick],[contenteditable=true]')]
                .filter((el) => {
                  const r = el.getBoundingClientRect();
                  const s = getComputedStyle(el);
                  return r.width > 2 && r.height > 2 && s.display !== 'none' && s.visibility !== 'hidden' && r.bottom > 0 && r.top < innerHeight * 3;
                })
                .slice(0, 200);
              const lines = els.map((el, i) => {
                const label = (el.innerText || el.value || el.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ').slice(0, 60);
                return '[' + i + '] ' + el.tagName + ' "' + label + '"';
              });
              const body = document.body ? document.body.innerText : '';
              const text = body.replace(/\n{3,}/g, '\n\n').slice(0, 4000);
              return 'PAGE ' + document.title + ' | ' + location.href + '\nTEXT\n' + text + '\nELEMENTS\n' + lines.join('\n');
            })()
        """

        const val JS_CLICK_INDEX = """
            (() => {
              const els = [...document.querySelectorAll('a[href],button,input,textarea,select,[role=button],[role=link],[role=tab],[onclick],[contenteditable=true]')]
                .filter((el) => {
                  const r = el.getBoundingClientRect();
                  const s = getComputedStyle(el);
                  return r.width > 2 && r.height > 2 && s.display !== 'none' && s.visibility !== 'hidden' && r.bottom > 0 && r.top < innerHeight * 3;
                })
                .slice(0, 200);
              const el = els[__INDEX__];
              if (!el) return 'notfound';
              el.scrollIntoView({ block: 'center' });
              el.focus();
              el.click();
              return 'ok';
            })()
        """

        const val JS_TYPE_INDEX = """
            (() => {
              const els = [...document.querySelectorAll('input,textarea,[contenteditable=true]')]
                .filter((el) => {
                  const r = el.getBoundingClientRect();
                  const s = getComputedStyle(el);
                  return r.width > 2 && r.height > 2 && s.display !== 'none' && s.visibility !== 'hidden';
                });
              const el = els[__INDEX__];
              if (!el) return 'notfound';
              el.focus();
              const text = __TEXT__;
              const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              const desc = Object.getOwnPropertyDescriptor(proto, 'value');
              if (desc && desc.set) desc.set.call(el, text); else el.innerText = text;
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              if (__SUBMIT__) {
                if (el.form && el.form.requestSubmit) el.form.requestSubmit();
                else el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
              }
              return 'ok';
            })()
        """

        const val JS_SCROLL = "(() => { window.scrollBy(0, __DY__); return String(window.scrollY); })()"
    }
}
