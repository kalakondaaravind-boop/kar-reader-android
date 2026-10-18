package com.kar.reader.feature.epub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Actions added to the text-selection menu. */
enum class SelectionAction(val label: String) { HIGHLIGHT("Highlight"), NOTE("Note"), WORD("Save word"), CITE("Cite"), QUOTE("Quote card") }

/**
 * WebView configured for offline, script-free book content:
 * - every request is answered from the open EPUB or refused (no network, no file system access);
 * - publisher JavaScript is blocked by a Content-Security-Policy header on every chapter;
 * - the selection menu gains Highlight, Note, Save word, Cite and Quote card.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class KarWebView(context: Context) : WebView(context) {

    var session: EpubSession? = null
    var chapterCss: String? = null
    var onSelectionAction: ((SelectionAction) -> Unit)? = null
    var onPageLoaded: ((String) -> Unit)? = null
    var onLinkToChapter: ((String) -> Unit)? = null
    var onExternalLinkBlocked: (() -> Unit)? = null

    init {
        settings.apply {
            // Needed only for the reader's own helpers (selection, highlights, position). Book scripts are blocked by CSP.
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = false
            blockNetworkImage = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        isVerticalScrollBarEnabled = true
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                val s = session
                if (url.host != EpubSession.HOST || s == null || (url.scheme != "https" && url.scheme != "http")) return blocked()
                val res = runCatching { s.resource(url.path.orEmpty(), chapterCss) }.getOrNull() ?: return notFound()
                return WebResourceResponse(res.mimeType, res.encoding, res.stream).apply {
                    responseHeaders = mapOf(
                        "Content-Security-Policy" to
                            "default-src 'none'; img-src https://${EpubSession.HOST} data:; style-src https://${EpubSession.HOST} 'unsafe-inline'; " +
                            "font-src https://${EpubSession.HOST} data:; media-src https://${EpubSession.HOST}; script-src 'none'",
                        "Cache-Control" to "no-store",
                    )
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == EpubSession.HOST) {
                    val current = view.url?.let { android.net.Uri.parse(it) }
                    if (current != null && current.path == url.path) return false // same chapter, fragment jump
                    onLinkToChapter?.invoke(android.net.Uri.decode(url.path.orEmpty().removePrefix("/")) + (url.fragment?.let { "#$it" } ?: ""))
                    return true
                }
                onExternalLinkBlocked?.invoke()
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(READER_JS, null)
                onPageLoaded?.invoke(url)
            }
        }
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))

    private fun wrap(original: ActionMode.Callback): ActionMode.Callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val ok = original.onCreateActionMode(mode, menu)
            SelectionAction.entries.forEachIndexed { i, a -> menu.add(Menu.NONE, MENU_BASE + i, i, a.label) }
            return ok
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = original.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val index = item.itemId - MENU_BASE
            if (index in SelectionAction.entries.indices) {
                onSelectionAction?.invoke(SelectionAction.entries[index])
                mode.finish()
                return true
            }
            return original.onActionItemClicked(mode, item)
        }

        override fun onDestroyActionMode(mode: ActionMode) = original.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }

    companion object {
        private const val MENU_BASE = 0x4B41
    }
}
