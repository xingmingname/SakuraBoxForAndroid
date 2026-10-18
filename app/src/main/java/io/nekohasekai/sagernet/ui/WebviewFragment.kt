package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.net.Uri
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.net.URLConnection
import io.nekohasekai.sagernet.SagerNet
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import moe.matsuri.nb4a.utils.WebViewUtil

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    lateinit var mWebView: WebView
    private var panelClosed = false
    @Volatile private var panelOrigin: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        panelClosed = false

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        mWebView = binding.webview
        mWebView.settings.domStorageEnabled = true
        mWebView.settings.javaScriptEnabled = true
        mWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                // Chromium may include a WebSocket query token in network-error
                // console messages. Once this view has held a capability, also
                // consume late messages while navigating away or stopping.
                // Request failures still reach YACD; no credential goes to logs.
                if (panelOrigin != null) return true
                return super.onConsoleMessage(message)
            }
        }
        mWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                val privateOrigin = panelOrigin?.let(Uri::parse)
                if (privateOrigin != null && uri.scheme == privateOrigin.scheme && uri.host == privateOrigin.host && uri.port == privateOrigin.port) {
                    // Never fetch the authenticated frontend from a TCP listener:
                    // a different app could acquire its port while the VPN is off.
                    if (!DataStore.serviceState.connected) return panelResponse(503)
                    return privatePanelFile(uri)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        refreshPanel()
    }

    /** Called by the existing activity state notification; never controls VPN. */
    fun refreshPanel() {
        if (!::mWebView.isInitialized || panelClosed) return
        mWebView.stopLoading()
        val uri = runCatching { Uri.parse(DataStore.yacdURL) }.getOrNull()
        val builtIn = uri?.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost", "::1") &&
            (uri.path == "/ui" || uri.path.orEmpty().startsWith("/ui/"))
        if (!builtIn) {
            // A custom remote panel is never automatically given our capability.
            mWebView.loadUrl(DataStore.yacdURL)
            return
        }
        if (!DataStore.serviceState.connected) {
            mWebView.loadDataWithBaseURL(null,
                "<meta charset=\"utf-8\">Start the connection with Clash API enabled, then reopen this panel.",
                "text/html", "UTF-8", null)
            return
        }
        try {
            val credentials = DataStore.activeClashApi()
            val html = clashPanelBootstrap(DataStore.yacdURL, credentials)
            panelOrigin = credentials.panelOrigin
            mWebView.loadDataWithBaseURL(credentials.panelOrigin + "/ui/", html, "text/html", "UTF-8", null)
        } catch (_: Exception) {
            // Neither a credential, config nor URL is put into logs/error HTML.
            mWebView.loadDataWithBaseURL(null,
                "<meta charset=\"utf-8\">Local panel authentication is unavailable. Check the API and panel settings, then restart the connection.",
                "text/html", "UTF-8", null)
        }
    }

    private fun panelResponse(status: Int): WebResourceResponse = WebResourceResponse(
        "text/plain", "UTF-8", status, "Unavailable", mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to CLASH_PANEL_CSP, "Referrer-Policy" to "no-referrer"),
        ByteArrayInputStream(ByteArray(0)))

    private fun privatePanelFile(uri: Uri): WebResourceResponse {
        return try {
            val path = uri.path.orEmpty()
            if (path != "/ui" && !path.startsWith("/ui/")) return panelResponse(404)
            val root = File(SagerNet.application.filesDir, "yacd").canonicalFile
            val relative = path.removePrefix("/ui").trimStart('/').ifEmpty { "index.html" }
            val file = File(root, relative).canonicalFile
            if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return panelResponse(404)
            val mime = when (file.extension.lowercase()) {
                "js", "mjs" -> "application/javascript"
                "css" -> "text/css"
                "html" -> "text/html"
                else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            }
            WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to CLASH_PANEL_CSP, "Referrer-Policy" to "no-referrer"), FileInputStream(file))
        } catch (_: Exception) { panelResponse(404) }
    }

    override fun onDestroyView() {
        if (::mWebView.isInitialized && !panelClosed) {
            panelClosed = true
            mWebView.stopLoading()
            mWebView.destroy()
        }
        super.onDestroyView()
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DataStore.yacdURL = view.text.toString()
                        refreshPanel()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                panelClosed = true
                mWebView.onPause()
                mWebView.removeAllViews()
                mWebView.destroy()
            }
        }
        return true
    }
}
