package za.co.tvbrowser

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.inputmethod.InputMethodManager
import android.content.Context
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
    private var edgeScrollActive = false
    private val edgeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var edgeDirection = 0
    private val edgeScroller = object : Runnable {
        override fun run() {
            if (edgeDirection == 0 || showingHome || fullscreen != null || !::web.isInitialized) return
            web.scrollBy(0, edgeDirection * (web.height / 12).coerceAtLeast(24))
            edgeHandler.postDelayed(this, 70)
        }
    }
    private fun updateEdgeScroll(y: Float, active: Boolean) {
        val height = frame.height.toFloat()
        val direction = if (!active || showingHome || fullscreen != null || height <= 0) 0
            else if (y >= height - 65f) 1 else if (y <= 24f) -1 else 0
        if (direction == edgeDirection) return
        edgeDirection = direction
        edgeHandler.removeCallbacks(edgeScroller)
        if (direction != 0) edgeHandler.post(edgeScroller)
    }
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
    private val accent = Color.rgb(118, 232, 179)
    private fun panel(color: Int, radius: Float = 22f, outline: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color); cornerRadius = radius
            if (outline != Color.TRANSPARENT) setStroke(2, outline)
        }
    private fun action(label: String, block: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.rgb(236, 245, 248))
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = panel(Color.rgb(35, 52, 67), 46f)
        elevation = 10f
        stateListAnimator = null
        setPadding(14, 5, 14, 5)
        setOnFocusChangeListener { _, focused ->
            animate().scaleX(if (focused) 1.09f else 1f)
                .scaleY(if (focused) 1.09f else 1f)
                .translationZ(if (focused) 12f else 0f).setDuration(150).start()
            background = panel(if (focused) Color.rgb(53, 116, 105) else Color.rgb(35, 52, 67), 46f)
        }
        setOnClickListener { block() }
    }
    private fun styleInput(input: EditText) {
        input.background = panel(Color.rgb(32, 49, 67), 46f)
        input.setPadding(24, 10, 18, 10)
        input.setSelectAllOnFocus(true)
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        input.isFocusableInTouchMode = true
        input.setOnFocusChangeListener { _, focused ->
            input.background = panel(if (focused) Color.rgb(42, 65, 80) else Color.rgb(32, 49, 67), 46f)
            if (focused) input.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    private fun browse(value: String) {
        val query = value.trim()
        if (query.isBlank()) return
        val target = when {
            query.startsWith("https://") || query.startsWith("http://") -> query
            query.contains(".") && !query.contains(" ") -> "https://$query"
            else -> "https://www.google.com/search?q=${Uri.encode(query)}"
        }
        updateEdgeScroll(0f, false)
        detectedVideo = null; lastPrompted = ""; videoButton.isEnabled = false
        showingHome = false; home.visibility = View.GONE; web.visibility = View.VISIBLE
        toolbar.visibility = View.VISIBLE; drawer.visibility = View.GONE
        web.loadUrl(target)
    }
    private fun showHome() {
        updateEdgeScroll(0f, false)
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
        toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(10, 8, 10, 8); setBackgroundColor(Color.rgb(16, 27, 41)); gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(action("⌂") { showHome() })
        toolbar.addView(action("←") { if (web.canGoBack()) web.goBack() })
        toolbar.addView(action("→") { if (web.canGoForward()) web.goForward() })
        toolbar.addView(action("↻") { web.reload() })
        urlInput = EditText(this).apply {
            setSingleLine(true); hint = "Search or enter URL"; setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setOnEditorActionListener { _, _, _ -> browse(text.toString()); true }
        }
        styleInput(urlInput)
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
                    web.evaluateJavascript("""
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

        }
        browserColumn.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(browserColumn, FrameLayout.LayoutParams(-1, -1))
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(105, 28, 36, 22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(12, 28, 43), Color.rgb(23, 45, 58), Color.rgb(12, 22, 37))).apply { cornerRadius = 0f }
        }
        home.addView(TextView(this).apply {
            text = "NOVA  /  TV BROWSER"; textSize = 30f; letterSpacing = 0.10f
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            setTextColor(accent); setPadding(0, 5, 0, 18)
        })
        val shortcuts = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
        for ((name, link) in listOf("Google" to "https://google.com", "YouTube" to "https://youtube.com", "Twitch" to "https://twitch.tv", "Facebook" to "https://facebook.com")) {
            shortcuts.addView(action(name) { browse(link) }, LinearLayout.LayoutParams(0, 76, 1f).apply { setMargins(0, 0, 12, 0) })
        }
        home.addView(shortcuts)
        home.addView(TextView(this).apply { text = "Discover more on the big screen"; textSize = 19f; setTextColor(Color.rgb(183, 205, 217)); setPadding(0, 16, 0, 12) })
        val search = EditText(this).apply {
            hint = "Search or enter URL"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setSingleLine(true); textSize = 23f
            setOnEditorActionListener { _, _, _ -> browse(text.toString()); true }
        }
        styleInput(search)
        val searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(search, LinearLayout.LayoutParams(0, 68, 1f))
        searchRow.addView(action("Search  →") { browse(search.text.toString()) }, LinearLayout.LayoutParams(160, 68).apply { leftMargin = 12 })
        home.addView(searchRow)
        home.addView(TextView(this).apply { text = "PHONE TOUCHPAD   •   SCAN TO PAIR"; textSize = 17f; letterSpacing = 0.08f; setTextColor(accent); setPadding(0, 24, 0, 10) })
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
                    "go" -> browse(if (value.isNotBlank()) value else if (showingHome) search.text.toString() else urlInput.text.toString())
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
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(28, 47, 62), Color.rgb(16, 29, 44))).apply { cornerRadius = 34f }
            elevation = 18f
            setPadding(12, 25, 12, 10)
        }
        for ((name, callback) in listOf<Pair<String, () -> Unit>>(
            "⌂ Home" to { showHome() }, "← Back" to { if (web.canGoBack()) web.goBack() },
            "★ Bookmark" to { getSharedPreferences("browser", MODE_PRIVATE).edit().putString("bookmark", web.url).apply(); toast("Saved") },
            "★ Open saved" to { getSharedPreferences("browser", MODE_PRIVATE).getString("bookmark", null)?.let { browse(it) } },
            "▶ Video" to { playVideo() }, "↓ Download" to { download() },
            "⚙ Hide menu" to { drawer.visibility = View.GONE }
        )) drawer.addView(action(name, callback), LinearLayout.LayoutParams(-1, 58).apply { bottomMargin = 12 })
        frame.addView(drawer, FrameLayout.LayoutParams(210, -1, Gravity.START))
        cursor = CursorOverlay()
        frame.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        // Decorative overlay only: do not intercept real mouse/touch events.
        cursor.isClickable = false
        cursor.isFocusable = false
        setContentView(frame)
        toolbar.visibility = View.GONE; web.visibility = View.GONE; drawer.visibility = View.GONE
    }
    private fun closeFullscreen() {
        fullscreen?.let { frame.removeView(it) }
        fullscreen = null; fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
        if (!showingHome) toolbar.visibility = View.VISIBLE
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
            (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_MOVE)) {
            cursor.visible = false
            cursor.invalidate()
            if (fullscreen == null) {
                if (event.x < 28) drawer.visibility = View.VISIBLE
                else if (event.x > 220) drawer.visibility = View.GONE
                updateEdgeScroll(event.y, true)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) updateEdgeScroll(0f, false)
        return super.dispatchGenericMotionEvent(event)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus is EditText) return super.dispatchKeyEvent(event)
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
    override fun onDestroy() { edgeHandler.removeCallbacks(edgeScroller); remote?.stop(); web.destroy(); super.onDestroy() }
    inner class CursorOverlay : View(this@MainActivity) {
        var px = 400f; var py = 250f; var visible = false
        private val paint = Paint(3).apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(3f, 1f, 1f, Color.BLACK) }
        fun move(dx: Float, dy: Float) {
            px = (px + dx).coerceIn(0f, width.toFloat().coerceAtLeast(1f))
            py = (py + dy).coerceIn(0f, height.toFloat().coerceAtLeast(1f))
            visible = true
            drawer.visibility = if (px < 35) View.VISIBLE else View.GONE
            updateEdgeScroll(py, true)
            invalidate()
        }
        fun click(target: WebView) {
            if (!visible) return
            val point = IntArray(2)
            target.getLocationOnScreen(point)
            if (!showingHome && px >= point[0] && py >= point[1] &&
                px < point[0] + target.width && py < point[1] + target.height) {
                sendTap(target, px - point[0], py - point[1])
            } else {
                val child = findClickable(frame, px, py)
                if (child != null) {
                    val location = IntArray(2); child.getLocationOnScreen(location)
                    if (child is EditText) {
                        child.requestFocus()
                        child.setSelection(child.text.length)
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(child, InputMethodManager.SHOW_IMPLICIT)
                    } else sendTap(child, px - location[0], py - location[1])
                }
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
    private fun sendTap(view: View, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0))
    }
    private fun findClickable(group: ViewGroup, x: Float, y: Float): View? {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE || child === cursor) continue
            val loc = IntArray(2); child.getLocationOnScreen(loc)
            if (x < loc[0] || y < loc[1] || x >= loc[0] + child.width || y >= loc[1] + child.height) continue
            if (child is ViewGroup) findClickable(child, x, y)?.let { return it }
            if (child.isClickable || child is EditText) return child
        }
        return null
    }
}
