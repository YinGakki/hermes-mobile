package com.nous.hermes.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Hosts a WebView that loads the locally-running hermes-web-ui
 * dashboard (http://localhost:8648). The server itself is started
 * by HermesStudioInstaller.start() before this activity is launched.
 *
 * Back button navigates WebView history (so the user can click
 * links inside the dashboard without being kicked out). Long-press
 * back exits the activity.
 *
 * Additionally, a left-edge swipe-right gesture provides an elegant
 * way to exit: swipe from the left edge shows a "← 退出" hint that
 * follows the finger; release past the threshold to exit.
 *
 * The WebView is configured for a full web-app experience:
 *   - JS enabled
 *   - DOM storage enabled (for Socket.IO / localStorage)
 *   - File access enabled (for uploads/downloads)
 *   - Mixed content allowed (localhost serves over HTTP)
 *   - Web Notifications API bridged to Android native notifications
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        const val EXTRA_BASE_URL = "base_url"
        private const val FILE_CHOOSER_REQUEST = 10042
        private const val NOTIF_CHANNEL_ID = "hermes_webui_notifications"
        private const val NOTIF_PERMISSION_REQUEST = 10043
        private const val PREF_SWIPE_HINT_SHOWN = "swipe_exit_hint_shown"
    }

    private lateinit var webView: WebView
    private var baseUrl: String = HermesStudioInstaller.STUDIO_BASE_URL
    private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
            ?: HermesStudioInstaller.STUDIO_BASE_URL

        createNotificationChannel()

        // 使用自定义 FrameLayout 容器，支持左边缘滑动手势退出 WebUI。
        // 从屏幕左边缘向右滑动，显示跟随手指的"← 退出"指示器，
        // 滑动超过阈值松手即退出，未超过则回弹隐藏。界面无常驻按钮。
        val container = SwipeExitFrameLayout(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Bridge JS → Android native notifications
            addJavascriptInterface(
                WebNotificationBridge(this@ChatActivity),
                "AndroidNotifications"
            )

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    val host = url.host ?: return false
                    if (host == "localhost" || host == "127.0.0.1") {
                        return false
                    }
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

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectNotificationPolyfill(view)
                }
            }

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
                    request?.grant(request.resources)
                }
            }
        }

        container.addView(webView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(container)

        // 首次进入提示边缘滑动退出手势
        container.setSwipeHint(PREF_SWIPE_HINT_SHOWN, "从屏幕左边缘向右滑动可退出 WebUI")

        Log.i(TAG, "Loading $baseUrl in WebView")
        webView.loadUrl(baseUrl)
    }

    /**
     * Create the notification channel (required Android 8+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Hermes WebUI 通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "来自 hermes-web-ui 的通知"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Inject a JavaScript polyfill that bridges the Web Notifications API
     * to our AndroidNotifications Java interface.
     *
     * Android WebView does NOT support window.Notification natively,
     * so hermes-web-ui shows "当前浏览器不支持通知". This polyfill
     * overrides window.Notification so the web app thinks the browser
     * supports notifications, and actual notifications are displayed
     * via Android's NotificationManager.
     */
    private fun injectNotificationPolyfill(view: WebView?) {
        val js = """
            (function() {
                if (window.__hermesNotifPolyfill) return;
                window.__hermesNotifPolyfill = true;

                function NotifPermission() {}

                // The bridge object exposed via addJavascriptInterface
                var bridge = window.AndroidNotifications;
                if (!bridge) {
                    console.warn('[Hermes] AndroidNotifications bridge not found');
                    return;
                }

                // Map Web Notification → Android Notification
                function HermesNotification(title, options) {
                    if (!(this instanceof HermesNotification)) {
                        return new HermesNotification(title, options);
                    }
                    options = options || {};
                    this.title = title;
                    this.body = options.body || '';
                    this.icon = options.icon || '';
                    this.tag = options.tag || '';
                    this.data = options.data || null;

                    // Fire show event asynchronously
                    var self = this;
                    setTimeout(function() {
                        if (typeof self.onshow === 'function') self.onshow();
                    }, 0);

                    // Send to Android
                    try {
                        bridge.showNotification(
                            String(title),
                            JSON.stringify({
                                body: self.body,
                                icon: self.icon,
                                tag: self.tag
                            })
                        );
                    } catch (e) {
                        console.error('[Hermes] showNotification failed: ' + e);
                    }
                }
                HermesNotification.prototype = {
                    close: function() {
                        if (typeof this.onclose === 'function') this.onclose();
                    }
                };

                // permission is always "granted" — we handle the Android
                // permission internally. requestPermission() resolves to
                // "granted" immediately (or after Android permission dialog).
                HermesNotification.permission = 'granted';

                HermesNotification.requestPermission = function(callback) {
                    var cb = callback || function() {};
                    // Check + request Android permission synchronously.
                    // hasPermission() returns true if already granted.
                    // requestPermission() triggers the Android dialog if needed.
                    var has = bridge.hasPermission();
                    if (!has) {
                        bridge.requestPermission();
                    }
                    // Optimistically resolve to 'granted' — if the user denies
                    // the Android dialog, notifications just won't show (silent
                    // no-op), but the web app still works normally.
                    HermesNotification.permission = 'granted';
                    try { cb('granted'); } catch(e) {}
                    return Promise.resolve('granted');
                };

                // Override window.Notification
                Object.defineProperty(window, 'Notification', {
                    value: HermesNotification,
                    writable: false,
                    configurable: true
                });

                console.log('[Hermes] Web Notifications API polyfill installed');
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    // 边缘滑动退出容器与首次提示 Toast 已提取至 [SwipeExitFrameLayout]。

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 直接退出 WebUI Activity。
            // 之前用 webView.canGoBack() → goBack() 会导致 SPA 每次路由变化
            // 都压入历史栈，canGoBack() 永远为 true，用户无法退出。
            // WebUI 内部有自己的页面导航（按钮/链接），不需要系统返回键来导航。
            finish()
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "POST_NOTIFICATIONS permission: ${if (granted) "granted" else "denied"}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        Log.i(TAG, "ChatActivity destroyed, WebView cleaned up")
    }

    /**
     * JavaScript interface exposed as window.AndroidNotifications.
     * Bridges Web Notifications API calls to Android's NotificationManager.
     *
     * IMPORTANT: methods annotated with @JavascriptInterface are called
     * on a background thread, not the UI thread. All UI/NotificationManager
     * calls are thread-safe so this is fine.
     */
    inner class WebNotificationBridge(private val context: Context) {

        @JavascriptInterface
        fun showNotification(title: String, optionsJson: String) {
            try {
                val opts = JSONObject(optionsJson)
                val body = opts.optString("body", "")
                val tag = opts.optString("tag", "")

                val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)

                val notifId = if (tag.isNotEmpty()) tag.hashCode() else System.currentTimeMillis().toInt()

                val manager = context.getSystemService(NotificationManager::class.java)
                manager.notify(notifId, builder.build())
                Log.d(TAG, "Notification shown: $title")
            } catch (e: Exception) {
                Log.e(TAG, "showNotification failed: ${e.message}")
            }
        }

        /**
         * Synchronously check if POST_NOTIFICATIONS permission is granted.
         * Called from JS on a background thread (no UI thread issues).
         */
        @JavascriptInterface
        fun hasPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        /**
         * Trigger the Android POST_NOTIFICATIONS permission dialog.
         * Must run on UI thread (requestPermissions requires it).
         * Fire-and-forget — result comes back via onRequestPermissionsResult.
         */
        @JavascriptInterface
        fun requestPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    webView.post {
                        requestPermissions(
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                            NOTIF_PERMISSION_REQUEST
                        )
                    }
                }
            }
        }
    }
}
