package za.co.tvbrowser

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Surface
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.net.URI

private val mint = Color(0xFF79E8B6)
private val ink = Color(0xFF0D1929)
private val slate = Color(0xFF24394B)
private val white = Color(0xFFF2F9FB)

class ComposeMainActivity : ComponentActivity() {
    private var remote: PhoneRemote? = null
    private var web: WebView? = null
    private var video by mutableStateOf<String?>(null)
    private var videoPrompt by mutableStateOf(false)
    private var downloadPrompt by mutableStateOf(false)
    private var currentUrl by mutableStateOf("")
    private var page by mutableStateOf(false)
    private var menu by mutableStateOf(false)
    private var address by mutableStateOf("")
    private var homeSearch by mutableStateOf("")
    private var pairingLink by mutableStateOf<String?>(null)
    private var cursorX by mutableFloatStateOf(410f)
    private var cursorY by mutableFloatStateOf(260f)
    private var cursorVisible by mutableStateOf(false)
    private var screenWidth = 1280f
    private var screenHeight = 720f
    private var pendingDownload: String? = null
    private var lastPrompt = ""
    private var edgeScrollDirection = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scrollTick = object : Runnable {
        override fun run() {
            if (edgeScrollDirection == 0 || !page) return
            web?.scrollBy(0, edgeScrollDirection * 38)
            handler.postDelayed(this, 65)
        }
    }
    private val saveToUsb = registerForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        val url = pendingDownload
        pendingDownload = null
        if (uri != null && url != null) Thread {
            try {
                java.net.URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
                    .getInputStream().use { input -> contentResolver.openOutputStream(uri)?.use { input.copyTo(it) } }
                runOnUiThread { toast("Video saved") }
            } catch (e: Exception) { runOnUiThread { toast("Download failed: ${e.message}") } }
        }.start()
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun navigate(value: String) {
        val query = value.trim()
        if (query.isBlank()) return
        val url = when {
            query.startsWith("https://", true) || query.startsWith("http://", true) -> query
            query.contains(".") && !query.contains(" ") -> "https://$query"
            else -> "https://www.google.com/search?q=${Uri.encode(query)}"
        }
        video = null; lastPrompt = ""; page = true; menu = false
        currentUrl = url; address = url
        web?.loadUrl(url)
    }
    private fun home() { edge(0); page = false; menu = false }
    private fun play() {
        val link = video ?: return toast("No supported video detected")
        videoPrompt = false
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", link))
    }
    private fun download() {
        if (video == null) return toast("No supported video detected")
        downloadPrompt = true
    }
    private fun saveInternal() {
        val link = video ?: return
        try {
            val request = DownloadManager.Request(Uri.parse(link))
                .setTitle("NOVA video").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MOVIES, "nova-${System.currentTimeMillis()}.mp4")
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast("Download started")
        } catch (e: Exception) { toast("Download failed: ${e.message}") }
        downloadPrompt = false
    }
    private fun detect(link: String) {
        val uri = Uri.parse(link)
        if (uri.scheme !in listOf("http", "https")) return
        val path = uri.path.orEmpty().lowercase()
        if (listOf(".mp4", ".m3u8", ".mpd", ".webm", ".m4v").none { path.endsWith(it) }) return
        runOnUiThread {
            if (!page || link == video) return@runOnUiThread
            video = link
            if (lastPrompt != link) { lastPrompt = link; videoPrompt = true }
        }
    }
    private fun createBrowser(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        isFocusableInTouchMode = true
        addJavascriptInterface(object {
            @JavascriptInterface fun found(link: String) { detect(link) }
        }, "NOVA")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                detect(url)
                val host = try { URI(url).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
                val blocked = listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net")
                return if (blocked.any { host == it || host.endsWith(".$it") })
                    WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                else null
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                currentUrl = url.orEmpty(); address = currentUrl
                view?.evaluateJavascript("""
                    (function(){
                      if(window.__novaScan)return;window.__novaScan=true;
                      const scan=()=>document.querySelectorAll('video,video source').forEach(v=>{
                        const src=v.currentSrc||v.src||v.getAttribute('src');
                        if(src&&/^https?:/.test(src))NOVA.found(src);
                      });
                      new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});
                      scan();
                    })();
                """.trimIndent(), null)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
        }
    }
    private fun qr(link: String): Bitmap {
        val bits = MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 240, 240)
        return Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until 240) for (x in 0 until 240)
                bitmap.setPixel(x, y, if (bits[x,y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    private fun edge(direction: Int) {
        if (edgeScrollDirection == direction) return
        edgeScrollDirection = direction
        handler.removeCallbacks(scrollTick)
        if (direction != 0) handler.post(scrollTick)
    }
    private fun move(dx: Float, dy: Float) {
        cursorVisible = true
        cursorX = (cursorX + dx).coerceIn(0f, screenWidth)
        cursorY = (cursorY + dy).coerceIn(0f, screenHeight)
        menu = cursorX < 32f
        edge(if (!page) 0 else if (cursorY >= screenHeight - 55f) 1 else if (cursorY < 25f) -1 else 0)
    }
    private fun tapCursor() {
        val target = if (page) web else null
        if (target != null && !menu) {
            val loc = IntArray(2); target.getLocationOnScreen(loc)
            val x = cursorX - loc[0]; val y = cursorY - loc[1]
            if (x >= 0 && y >= 0 && x < target.width && y < target.height) {
                val t = android.os.SystemClock.uptimeMillis()
                target.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0))
                target.dispatchTouchEvent(MotionEvent.obtain(t,t+80,MotionEvent.ACTION_UP,x,y,0))
            }
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus is android.widget.EditText || event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val delta = 29f
            when(event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { move(-delta,0f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { move(delta,0f); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { move(0f,-delta); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { move(0f,delta); return true }
                KeyEvent.KEYCODE_DPAD_CENTER -> if (cursorVisible && page && !menu) { tapCursor(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
            event.actionMasked in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE)) {
            cursorVisible = false
            val x = event.x; val y = event.y
            menu = x < 28f
            edge(if (!page) 0 else if (y > screenHeight - 55f) 1 else if (y < 25f) -1 else 0)
        }
        return super.dispatchGenericMotionEvent(event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = 5894
        web = createBrowser()
        try {
            remote = PhoneRemote { action, x, y, value -> runOnUiThread {
                when(action) {
                    "move" -> move(x.toFloat(),y.toFloat())
                    "click" -> tapCursor()
                    "back" -> back()
                    "home" -> home()
                    "play" -> play()
                    "scroll" -> web?.scrollBy(0,y*4)
                    "go" -> navigate(value)
                    "text" -> if(page) address=value else homeSearch=value
                }
            } }.also { it.start(5000,false) }
            pairingLink = remote?.address()
        } catch (e: Exception) { toast("Phone pairing unavailable: ${e.message}") }
        setContent { MaterialTheme { BrowserScreen() } }
    }
    private fun back() {
        when {
            videoPrompt -> videoPrompt = false
            downloadPrompt -> downloadPrompt = false
            menu -> menu = false
            page && web?.canGoBack() == true -> web?.goBack()
            page -> home()
            else -> finish()
        }
    }
    override fun onBackPressed() { back() }
    override fun onDestroy() {
        handler.removeCallbacks(scrollTick)
        remote?.stop()
        web?.destroy()
        super.onDestroy()
    }

    @Composable private fun BrowserScreen() {
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize().background(ink)) {
            screenWidth = with(density) { maxWidth.toPx() }
            screenHeight = with(density) { maxHeight.toPx() }
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(page) { TopBar() }
                Box(Modifier.fillMaxSize()) {
                    if (page) {
                        AndroidView(factory = { web!! }, modifier = Modifier.fillMaxSize())
                    } else HomeScreen()
                }
            }
            AnimatedVisibility(menu, modifier = Modifier.align(Alignment.CenterStart)) { SideMenu() }
            if (cursorVisible) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cursorX,cursorY); lineTo(cursorX,cursorY+28f); lineTo(cursorX+8f,cursorY+20f)
                        lineTo(cursorX+16f,cursorY+33f); lineTo(cursorX+21f,cursorY+30f)
                        lineTo(cursorX+13f,cursorY+18f); lineTo(cursorX+24f,cursorY+17f); close()
                    }
                    drawPath(p, Color.Black, style=Stroke(width=5f))
                    drawPath(p, white)
                }
            }
            if (videoPrompt) DialogPanel("Video detected", "Play this video in the fullscreen TV player?", "Play", { play() }, "Later", { videoPrompt=false })
            if (downloadPrompt) DialogPanel("Save video", "Choose the destination", "TV storage", { saveInternal() }, "USB / Folder", {
                downloadPrompt=false
                pendingDownload=video
                saveToUsb.launch("nova-${System.currentTimeMillis()}.mp4")
            })
        }
    }
    @Composable private fun TopBar() {
        Row(Modifier.fillMaxWidth().height(74.dp).background(Color(0xFF132435)).padding(horizontal=14.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            FloatingIcon(Icons.Rounded.Home,"Home") { home() }
            FloatingIcon(Icons.Rounded.ArrowBack,"Back") { back() }
            FloatingIcon(Icons.Rounded.ArrowForward,"Forward") { web?.goForward() }
            FloatingIcon(Icons.Rounded.Refresh,"Reload") { web?.reload() }
            UrlField(address, { address=it }, { navigate(address) }, Modifier.weight(1f))
            FloatingIcon(Icons.Rounded.PlayArrow,"Play",video!=null) { play() }
            FloatingIcon(Icons.Rounded.FileDownload,"Download") { download() }
            FloatingIcon(Icons.Rounded.Menu,"Menu") { menu=!menu }
        }
    }
    @Composable private fun HomeScreen() {
        val background = Brush.linearGradient(listOf(Color(0xFF0B1D30),Color(0xFF1A3A45),Color(0xFF10192B)))
        Column(Modifier.fillMaxSize().background(background).padding(start=105.dp,top=35.dp,end=44.dp,bottom=20.dp), verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                Text("NOVA",fontSize=40.sp,fontWeight=FontWeight.Bold,color=mint)
                Text("BROWSER",fontSize=19.sp,color=white.copy(alpha=.7f))
            }
            Text("Your web. Your screen.",fontSize=25.sp,fontWeight=FontWeight.Medium,color=white)
            Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                UrlField(homeSearch,{homeSearch=it},{navigate(homeSearch)},Modifier.weight(1f))
                FloatingIcon(Icons.Rounded.Search,"Search") { navigate(homeSearch) }
            }
            Text("QUICK ACCESS",fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=mint)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                items(listOf("Google" to "https://google.com","YouTube" to "https://youtube.com","Twitch" to "https://twitch.tv","Facebook" to "https://facebook.com")) { (name,url) ->
                    FloatingTile(name) { navigate(url) }
                }
            }
            pairingLink?.let { link ->
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    Image(bitmap=remember(link) { qr(link).asImageBitmap() },contentDescription="Phone pairing QR",modifier=Modifier.size(160.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(8.dp))
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("PAIR YOUR PHONE",fontSize=14.sp,fontWeight=FontWeight.Bold,color=mint)
                        Text("Scan the QR code on the same Wi-Fi network",fontSize=17.sp,color=white)
                        Text(link.substringBefore("?"),fontSize=14.sp,color=white.copy(alpha=.7f))
                    }
                }
            }
        }
    }
    @Composable private fun UrlField(value:String,onChange:(String)->Unit,onSubmit:()->Unit,modifier:Modifier=Modifier) {
        Box(modifier.height(54.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF263C50)).padding(horizontal=20.dp),contentAlignment=Alignment.CenterStart) {
            BasicTextField(value,onChange,modifier=Modifier.fillMaxWidth(),singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(color=white,fontSize=18.sp),
                cursorBrush=androidx.compose.ui.graphics.SolidColor(mint),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Go),
                keyboardActions=KeyboardActions(onGo={onSubmit()},onDone={onSubmit()}),
                decorationBox={ inner -> if(value.isBlank()) Text("Search or enter URL",fontSize=17.sp,color=white.copy(alpha=.55f));inner() })
        }
    }
    @Composable private fun FloatingIcon(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,enabled:Boolean=true,onClick:()->Unit) {
        var focused by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(if(focused)1.10f else 1f,label="focus")
        Box(Modifier.size(50.dp).scale(scale).clip(RoundedCornerShape(18.dp))
            .background(if(focused)Color(0xFF366E65) else slate)
            .clickable(enabled=enabled,onClick=onClick)
            .onFocusChanged { focused=it.isFocused }
            .focusable(),contentAlignment=Alignment.Center) {
            Icon(icon,contentDescription=label,tint=if(enabled)white else white.copy(alpha=.35f),modifier=Modifier.size(26.dp))
        }
    }
    @Composable private fun FloatingTile(label:String,onClick:()->Unit) {
        var focused by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(if(focused)1.07f else 1f,label="tile")
        Box(Modifier.size(width=175.dp,height=86.dp).scale(scale).clip(RoundedCornerShape(24.dp))
            .background(if(focused)Color(0xFF356F67) else slate)
            .clickable(onClick=onClick).onFocusChanged { focused=it.isFocused }.focusable(),
            contentAlignment=Alignment.Center) {
            Text(label,fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=white)
        }
    }
    @Composable private fun SideMenu() {
        Column(Modifier.width(210.dp).clip(RoundedCornerShape(topEnd=30.dp,bottomEnd=30.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF28465A),Color(0xFF14283B))))
            .padding(12.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
            Text("NOVA",fontSize=24.sp,fontWeight=FontWeight.Bold,color=mint,modifier=Modifier.padding(12.dp))
            listOf(
                Triple(Icons.Rounded.Home,"Home",{home()}),
                Triple(Icons.Rounded.ArrowBack,"Back",{back()}),
                Triple(Icons.Rounded.BookmarkAdd,"Bookmark",{getSharedPreferences("browser",MODE_PRIVATE).edit().putString("bookmark",currentUrl).apply();toast("Saved")}),
                Triple(Icons.Rounded.Bookmarks,"Saved",{getSharedPreferences("browser",MODE_PRIVATE).getString("bookmark",null)?.let { navigate(it) }}),
                Triple(Icons.Rounded.PlayArrow,"Play video",{play()}),
                Triple(Icons.Rounded.FileDownload,"Download",{download()}),
                Triple(Icons.Rounded.Close,"Close",{menu=false})
            ).forEach { (icon,label,action) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick=action)
                    .padding(13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Icon(icon,contentDescription=null,tint=mint,modifier=Modifier.size(22.dp))
                    Text(label,fontSize=16.sp,color=white)
                }
            }
        }
    }
    @Composable private fun DialogPanel(title:String,description:String,primary:String,onPrimary:()->Unit,secondary:String,onSecondary:()->Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.65f)),contentAlignment=Alignment.Center) {
            Column(Modifier.width(430.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFF1A3042)).padding(30.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Text(title,fontSize=26.sp,fontWeight=FontWeight.Bold,color=white)
                Text(description,fontSize=17.sp,color=white.copy(alpha=.8f))
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    FloatingTile(primary,onPrimary)
                    FloatingTile(secondary,onSecondary)
                }
            }
        }
    }
}
