package com.iris.localai.browser

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.iris.localai.IrisApp

/** AI 浏览器页面：WebView 由 BrowserController 暴露给 Agent 工具。 */
class BrowserActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        setContent {
            var current by remember { mutableStateOf<WebView?>(null) }
            AndroidView(
                factory = { context ->
                    val graph = (context.applicationContext as IrisApp).graph
                    WebView(context).apply {
                        setBackgroundColor(Color.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.userAgentString = settings.userAgentString + " IrisAI"
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = !allowed(request.url.toString())

                            override fun onPageFinished(view: WebView, url: String?) {
                                graph.browser.noteUrl(url)
                                graph.browser.attach(view)
                            }
                        }
                        graph.browser.attach(this)
                        if (initialUrl.isNotBlank() && allowed(initialUrl)) loadUrl(initialUrl)
                        current = this
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            BackHandler(enabled = current?.canGoBack() == true) {
                current?.goBack()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        if (allowed(url)) webView?.loadUrl(url)
    }

    private fun allowed(url: String): Boolean {
        val graph = (applicationContext as? IrisApp)?.graph ?: return false
        return BrowserPolicy.check(url, graph.integrationStore.settings.value.browser) == null
    }

    override fun onDestroy() {
        webView?.let { view ->
            (applicationContext as? IrisApp)?.graph?.browser?.detach(view)
            runCatching { view.destroy() }
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
