package com.nous.hermes.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts a WebView that loads the locally-running hermes-web-ui
 * dashboard (http://localhost:8648). The server itself is started
 * by HermesStudioInstaller.start() before this activity is launched.
 *
 * Back button navigates WebView history (so the user can click
 * links inside the dashboard without being kicked out). Long-press
 * back exits the activity.
 *
 * The WebView is configured for a full web-app experience:
 *   - JS enabled
 *   - DOM storage enabled (for Socket.IO / localStorage)
 *   - File access enabled (for uploads/downloads)
 *   - Mixed content allowed (localhost serves over HTTP)
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        const val EXTRA_BASE_URL = "base_url"
        private const val FILE_CHOOSER_REQUEST = 10042
    }

    private lateinit var webView: WebView
    private var baseUrl: String = HermesStudioInstaller.STUDIO_BASE_URL
    private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
            ?: HermesStudioInstaller.STUDIO_BASE_URL

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Mixed content: hermes-web-ui serves over HTTP on localhost.
            // WebSocket (ws://) is also mixed content from an https page,
            // but we're loading http://localhost anyway so this is fine.
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Open external links (http/https outside localhost) in the
            // system browser, not inside the WebView.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    val host = url.host ?: return false
                    // Allow localhost + 127.0.0.1 to load inside the WebView.
                    if (host == "localhost" || host == "127.0.0.1") {
                        return false
                    }
                    // Everything else → system browser
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e(TAG, "WebView error: ${error?.description}")
                }
            }

            // Handle <input type="file"> — file chooser dialog
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    this@ChatActivity.filePathCallback?.onReceiveValue(null)
                    this@ChatActivity.filePathCallback = filePathCallback
                    try {
                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "File chooser failed: ${e.message}")
                        this@ChatActivity.filePathCallback = null
                        return false
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    // Auto-grant resources the page asks for (e.g. microphone
                    // for voice input). Consider showing a dialog instead
                    // if the user wants more control.
                    request?.grant(request.resources)
                }
            }
        }

        setContentView(webView)

        // Load the dashboard
        Log.i(TAG, "Loading $baseUrl in WebView")
        webView.loadUrl(baseUrl)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Navigate WebView history on Back press instead of finishing
        // the activity immediately. Only exit when there's no history.
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in API 34, but required for file chooser on older APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val callback = filePathCallback ?: return
            filePathCallback = null
            val uris = when {
                resultCode != Activity.RESULT_OK -> null
                data?.data != null -> arrayOf(data.data!!)
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                else -> null
            }
            callback.onReceiveValue(uris)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Important: destroy the WebView to release its native resources
        // and stop any background JS / WebSocket connections.
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        Log.i(TAG, "ChatActivity destroyed, WebView cleaned up")
    }
}
