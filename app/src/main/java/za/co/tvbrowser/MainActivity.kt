package za.co.tvbrowser

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.URI

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private var mediaUrl: String? = null
    private var pendingUsbUrl: String? = null
    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "popcash.net", "scorecardresearch.com"
    )
    private fun isBlocked(url: String): Boolean { return try {
        val host = URI(url).host?.lowercase() ?: return false
        blockedHosts.any { host == it || host.endsWith(".$it") }
    } catch (_: Exception) { false } }
    private fun detect(url: String) {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        if (listOf(".mp4", ".webm", ".m3u8", ".mpd").any { path.endsWith(it) }) {
            runOnUiThread { mediaUrl = url; status.text = "Video detected — Play / Download" }
        }
    }
    private val usbPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("video/*")) { uri ->
        val url = pendingUsbUrl
        if (uri != null && url != null) {
            status.text = "USB copy started"
            Thread {
                try {
                    val conn = java.net.URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
                    conn.getInputStream().use { input ->
                        contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                            ?: error("Cannot open destination")
                    }
                    runOnUiThread { status.text = "Video saved" }
                } catch (e: Exception) { runOnUiThread { status.text = "Save failed: ${e.message}" } }
            }.start()
        }
        pendingUsbUrl = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff101712.toInt()) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) {
            bar.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("←") { if (web.canGoBack()) web.goBack() }
        button("→") { if (web.canGoForward()) web.goForward() }
        button("↻") { web.reload() }
        address = EditText(this).apply {
            setSingleLine(true); hint = "Search or enter URL"
            setTextColor(-1); setHintTextColor(0xffaaaaaa.toInt())
            setOnEditorActionListener { _, _, _ ->
                val value = text.toString().trim()
                val url = if (value.startsWith("https://") || value.startsWith("http://")) value
                    else if (value.contains(".") && !value.contains(" ")) "https://$value"
                    else "https://www.google.com/search?q=" + Uri.encode(value)
                web.loadUrl(url); true
            }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        button("Play") {
            mediaUrl?.let { startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", it)) }
                ?: Toast.makeText(this, "No direct video detected", Toast.LENGTH_SHORT).show()
        }
        button("Save") {
            val url = mediaUrl
            if (url == null) Toast.makeText(this, "No direct video detected", Toast.LENGTH_SHORT).show()
            else {
                val options = arrayOf("TV storage", "USB / choose destination")
                android.app.AlertDialog.Builder(this).setTitle("Download video").setItems(options) { _, which ->
                    if (which == 0) {
                        val request = DownloadManager.Request(Uri.parse(url))
                            .setTitle("TV Browser video")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MOVIES, "video-${System.currentTimeMillis()}.mp4")
                        (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                        status.text = "Download queued"
                    } else {
                        pendingUsbUrl = url
                        usbPicker.launch("video-${System.currentTimeMillis()}.mp4")
                    }
                }.show()
            }
        }
        root.addView(bar)
        status = TextView(this).apply { text = "Protected browsing"; setTextColor(0xff9edfa5.toInt()); textSize = 13f }
        root.addView(status)
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                web.visibility = View.GONE
                root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
                view.tag = callback
                fullscreenView = view
            }
            override fun onHideCustomView() {
                fullscreenView?.let { root.removeView(it); (it.tag as? CustomViewCallback)?.onCustomViewHidden() }
                fullscreenView = null
                web.visibility = View.VISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                detect(url)
                return if (isBlocked(url)) WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0))) else null
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                address.setText(url ?: "")
                web.evaluateJavascript("""
                    (function(){
                      if(window.__tvBrowserObserver)return;
                      window.__tvBrowserObserver=true;
                      const scan=()=>{
                        const v=document.querySelector('video[src],video source[src]');
                        if(v){const src=v.currentSrc||v.src||v.getAttribute('src');
                          if(src && /^https?:/.test(src)) TVBrowser.found(src);}
                      };
                      new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});
                      scan();
                    })();
                """.trimIndent(), null)
            }
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun found(url: String) { detect(url) }
        }, "TVBrowser")
        web.loadUrl("https://www.google.com")
    }
    private var fullscreenView: View? = null
    override fun onBackPressed() {
        if (fullscreenView != null) web.webChromeClient?.onHideCustomView()
        else if (web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }
    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
