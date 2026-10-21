package za.co.tvbrowser

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.net.URI

class MainActivity : AppCompatActivity() {
    private lateinit var frame: FrameLayout
    private lateinit var web: WebView
    private lateinit var home: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var cursor: CursorOverlay
    private lateinit var urlInput: EditText
    private lateinit var videoButton: Button
    private var detectedVideo: String? = null
    private var lastPrompted = ""
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var remote: PhoneRemote? = null
    private var pendingDownload: String? = null
    private var showingHome = true
    private val blocked = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net")
    private val pickFile = registerForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        val link = pendingDownload
        if (uri != null && link != null) Thread {
            try {
                val connection = java.net.URL(link).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
                connection.getInputStream().use { input ->
                    contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) } ?: error("Destination not writable")
                }
                runOnUiThread { toast("Video saved") }
            } catch (e: Exception) { runOnUiThread { toast("Download failed: ${e.message}") } }
        }.start()
        pendingDownload = null
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun action(label: String, block: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { block() }
    }
    private fun browse(value: String) {
        val query = value.trim()
        if (query.isBlank()) return
        val target = when {
            query.startsWith("https://") || query.startsWith("http://") -> query
            query.contains(".") && !query.contains(" ") -> "https://$query"
            else -> "https://www.google.com/search?q=${Uri.encode(query)}"
        }
        detectedVideo = null; lastPrompted = ""; videoButton.isEnabled = false
        showingHome = false; home.visibility = View.GONE; web.visibility = View.VISIBLE
        toolbar.visibility = View.VISIBLE; drawer.visibility = View.GONE
        web.loadUrl(target)
    }
    private fun showHome() {
        showingHome = true; home.visibility = View.VISIBLE; web.visibility = View.GONE
        toolbar.visibility = View.GONE; drawer.visibility = View.GONE
    }
    private fun playVideo() {
        val link = detectedVideo
        if (link == null) { toast("No supported video found"); return }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", link))
    }
    private fun download() {
        val link = detectedVideo ?: return toast("No video detected")
        AlertDialog.Builder(this).setTitle("Save video").setItems(arrayOf("TV internal storage", "USB / choose folder")) { _, choice ->
            if (choice == 0) {
                try {
                    val request = DownloadManager.Request(Uri.parse(link))
                        .setTitle("TV Browser video")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MOVIES, "video-${System.currentTimeMillis()}.mp4")
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    toast("Download started")
                } catch (e: Exception) { toast("Download failed: ${e.message}") }
            } else {
                pendingDownload = link
                pickFile.launch("video-${System.currentTimeMillis()}.mp4")
            }
        }.show()
    }
    private fun detect(link: String) {
        val uri = Uri.parse(link)
        if (uri.scheme != "https" && uri.scheme != "http") return
        val path = uri.path.orEmpty().lowercase()
        if (listOf(".mp4", ".webm", ".m3u8", ".mpd", ".m4v").none { path.endsWith(it) }) return
        runOnUiThread {
            if (showingHome || link == detectedVideo) return@runOnUiThread
            detectedVideo = link; videoButton.isEnabled = true
            if (lastPrompted != link && !isFinishing) {
                lastPrompted = link
                AlertDialog.Builder(this).setTitle("Video detected")
                    .setMessage("Play this video in the fullscreen TV player?")
                    .setPositiveButton("Play") { _, _ -> playVideo() }
                    .setNeutralButton("Download") { _, _ -> download() }
                    .setNegativeButton("Stay on page", null).show()
            }
        }
    }
    private fun qr(link: String): Bitmap {
        val bits = MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 280, 280)
        return Bitmap.createBitmap(280, 280, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until 280) for (x in 0 until 280) bitmap.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(16, 23, 34)) }
        val browserColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(32, 39, 54)) }
        toolbar.addView(action("⌂") { showHome() })
        toolbar.addView(action("←") { if (web.canGoBack()) web.goBack() })
        toolbar.addView(action("→") { if (web.canGoForward()) web.goForward() })
        toolbar.addView(action("↻") { web.reload() })
        urlInput = EditText(this).apply {
            setSingleLine(true); hint = "Search or enter URL"; setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setOnEditorActionListener { _, _, _ -> browse(text.toString()); true }
        }
        toolbar.addView(urlInput, LinearLayout.LayoutParams(0, -2, 1f))
        videoButton = action("▶") { playVideo() }.apply { isEnabled = false }
        toolbar.addView(videoButton)
        toolbar.addView(action("↓") { download() })
        toolbar.addView(action("⋮") { drawer.visibility = View.VISIBLE })
        browserColumn.addView(toolbar)
        web = WebView(this).apply {
            isFocusable = true; isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun found(url: String) { detect(url) }
            }, "TVBrowser")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val link = request?.url?.toString() ?: return null
                    detect(link)
                    val host = try { URI(link).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
                    return if (blocked.any { host == it || host.endsWith(".$it") })
                        WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                    else null
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    urlInput.setText(url.orEmpty())
                    evaluateJavascript("""
                        (function(){
                          if(window.__tvScan)return;window.__tvScan=true;
                          const scan=()=>document.querySelectorAll('video,video source').forEach(v=>{
                            let src=v.currentSrc||v.src||v.getAttribute('src');
                            if(src && /^https?:/.test(src))TVBrowser.found(src);
                          });
                          new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});
                          scan();
                        })();
                    """.trimIndent(), null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) return
                    fullscreen = view; fullscreenCallback = callback
                    frame.addView(view, FrameLayout.LayoutParams(-1, -1))
                    toolbar.visibility = View.GONE
                }
                override fun onHideCustomView() { closeFullscreen() }
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE && event.x < 28) drawer.visibility = View.VISIBLE
                false
            }
        }
        browserColumn.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(browserColumn, FrameLayout.LayoutParams(-1, -1))
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(95, 35, 35, 20)
            setBackgroundColor(Color.rgb(18, 29, 43))
        }
        home.addView(TextView(this).apply { text = "TV BROWSER"; textSize = 29f; setTextColor(Color.WHITE) })
        val shortcuts = LinearLayout(this)
        for ((name, link) in listOf("Google" to "https://google.com", "YouTube" to "https://youtube.com", "Twitch" to "https://twitch.tv", "Facebook" to "https://facebook.com")) {
            shortcuts.addView(action(name) { browse(link) }, LinearLayout.LayoutParams(0, 85, 1f))
        }
        home.addView(shortcuts)
        val search = EditText(this).apply {
            hint = "Search or enter URL"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setSingleLine(true); textSize = 23f
            setOnEditorActionListener { _, _, _ -> browse(text.toString()); true }
        }
        home.addView(search)
        home.addView(TextView(this).apply { text = "Phone remote — scan QR on the same Wi-Fi"; textSize = 20f; setTextColor(Color.WHITE) })
        try {
            remote = PhoneRemote { act, x, y, value -> runOnUiThread {
                when (act) {
                    "move" -> cursor.move(x.toFloat(), y.toFloat())
                    "click" -> cursor.click(web)
                    "back" -> onBackPressed()
                    "home" -> showHome()
                    "play" -> playVideo()
                    "scroll" -> web.scrollBy(0, y * 4)
                    "text" -> if (showingHome) search.setText(value) else urlInput.setText(value)
                }
            } }.also { it.start(5000, false) }
            val link = remote?.address()
            if (link != null) {
                home.addView(ImageView(this).apply { setImageBitmap(qr(link)) }, LinearLayout.LayoutParams(260, 260))
                home.addView(TextView(this).apply { text = link.substringBefore("?"); setTextColor(Color.LTGRAY) })
            } else home.addView(TextView(this).apply { text = "Connect TV to Wi-Fi to enable phone remote"; setTextColor(Color.WHITE) })
        } catch (e: Exception) { toast("Phone remote unavailable: ${e.message}") }
        frame.addView(home, FrameLayout.LayoutParams(-1, -1))
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(29, 43, 60))
            setPadding(8, 25, 8, 10)
        }
        for ((name, callback) in listOf<Pair<String, () -> Unit>>(
            "⌂ Home" to { showHome() }, "← Back" to { if (web.canGoBack()) web.goBack() },
            "★ Bookmark" to { getSharedPreferences("browser", MODE_PRIVATE).edit().putString("bookmark", web.url).apply(); toast("Saved") },
            "★ Open saved" to { getSharedPreferences("browser", MODE_PRIVATE).getString("bookmark", null)?.let { browse(it) } },
            "▶ Video" to { playVideo() }, "↓ Download" to { download() },
            "⚙ Hide menu" to { drawer.visibility = View.GONE }
        )) drawer.addView(action(name, callback))
        frame.addView(drawer, FrameLayout.LayoutParams(210, -1, Gravity.START))
        cursor = CursorOverlay()
        frame.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        cursor.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_MOVE || event.action == MotionEvent.ACTION_MOVE) {
                if (event.x < 30) drawer.visibility = View.VISIBLE
                else if (event.x > 220) drawer.visibility = View.GONE
            }
            false
        }
        setContentView(frame)
        toolbar.visibility = View.GONE; web.visibility = View.GONE; drawer.visibility = View.GONE
    }
    private fun closeFullscreen() {
        fullscreen?.let { frame.removeView(it) }
        fullscreen = null; fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
        if (!showingHome) toolbar.visibility = View.VISIBLE
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && fullscreen == null && event.keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            val dx = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -28f; KeyEvent.KEYCODE_DPAD_RIGHT -> 28f; else -> 0f }
            val dy = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_UP -> -28f; KeyEvent.KEYCODE_DPAD_DOWN -> 28f; else -> 0f }
            cursor.move(dx, dy)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && cursor.visible) {
            cursor.click(web); return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onBackPressed() {
        when {
            fullscreen != null -> closeFullscreen()
            drawer.visibility == View.VISIBLE -> drawer.visibility = View.GONE
            !showingHome && web.canGoBack() -> web.goBack()
            !showingHome -> showHome()
            else -> super.onBackPressed()
        }
    }
    override fun onDestroy() { remote?.stop(); web.destroy(); super.onDestroy() }
    inner class CursorOverlay : View(this) {
        var px = 400f; var py = 250f; var visible = false
        private val paint = Paint(3).apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(3f, 1f, 1f, Color.BLACK) }
        fun move(dx: Float, dy: Float) {
            px = (px + dx).coerceIn(0f, width.toFloat().coerceAtLeast(1f))
            py = (py + dy).coerceIn(0f, height.toFloat().coerceAtLeast(1f))
            visible = true
            drawer.visibility = if (px < 35) View.VISIBLE else View.GONE
            invalidate()
        }
        fun click(target: WebView) {
            if (!visible) return
            val down = android.os.SystemClock.uptimeMillis()
            val x = px; val y = py
            // Dispatch at the actual screen coordinate to allow toolbar, drawer and home clicks.
            val hit = IntArray(2); target.getLocationOnScreen(hit)
            val webX = x - hit[0]; val webY = y - hit[1]
            if (!showingHome && webX >= 0 && webY >= 0 && webY < target.height) {
                target.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, webX, webY, 0))
                target.dispatchTouchEvent(MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, webX, webY, 0))
            } else {
                val rootPoint = IntArray(2); frame.getLocationOnScreen(rootPoint)
                val child = findClickable(frame, x, y)
                child?.performClick()
            }
        }
        override fun onDraw(canvas: Canvas) {
            if (!visible) return
            val path = android.graphics.Path().apply {
                moveTo(px, py); lineTo(px, py + 26); lineTo(px + 7, py + 20)
                lineTo(px + 14, py + 32); lineTo(px + 19, py + 29)
                lineTo(px + 12, py + 18); lineTo(px + 23, py + 17); close()
            }
            canvas.drawPath(path, paint)
        }
    }
    private fun findClickable(group: ViewGroup, x: Float, y: Float): View? {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE || child === cursor) continue
            val loc = IntArray(2); child.getLocationOnScreen(loc)
            if (x < loc[0] || y < loc[1] || x >= loc[0] + child.width || y >= loc[1] + child.height) continue
            if (child is ViewGroup) findClickable(child, x, y)?.let { return it }
            if (child.isClickable) return child
        }
        return null
    }
}
