package com.airtel.monitor

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.system.exitProcess
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Hosts the embedded Python server (via Chaquopy) and shows the dashboard
 * in a full-screen WebView pointed at the loopback server.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val serverUrl = "http://127.0.0.1:8080"

    // Android 16+/17 gate LAN access (192.168.1.1) behind this runtime permission.
    private val localNetPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    private val permRequestCode = 4242

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask for local-network access up front, or the router is unreachable.
        if (checkSelfPermission(localNetPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(localNetPermission), permRequestCode)
        }

        // Start Python once and launch the embedded server thread.
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        Python.getInstance().getModule("start").callAttr("start")

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Enables JS dialogs (alert/confirm) in the WebView, e.g. the Stop confirm.
        webView.webChromeClient = WebChromeClient()

        // Bridge so the "Stop App" button in the dashboard can quit the app.
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun stopApp() {
                runOnUiThread {
                    finishAndRemoveTask()
                    // Fully stop the embedded Python server + poller.
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                }
            }
        }, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // The server may still be binding on first launch; retry.
                if (request?.isForMainFrame == true) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.loadUrl(serverUrl)
                    }, 1000)
                }
            }
        }

        // Let the WebView navigate its own history with the back button.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // Give the server ~1.5s to bind port 8080, then load the dashboard.
        Handler(Looper.getMainLooper()).postDelayed({
            webView.loadUrl(serverUrl)
        }, 1500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permRequestCode) {
            // Reload once the user has answered so the dashboard picks up live data.
            Handler(Looper.getMainLooper()).postDelayed({
                webView.loadUrl(serverUrl)
            }, 800)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
