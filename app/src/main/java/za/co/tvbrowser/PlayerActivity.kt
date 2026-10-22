package za.co.tvbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var url: String
    private lateinit var movieTitle:String
    private fun seekToFraction(fraction:Float) { if(duration>0) { player.seekTo((duration*fraction.coerceIn(0f,1f)).toLong());revealControls() } }
    private var subtitleUri: Uri? = null
    private var subtitleType = MimeTypes.TEXT_VTT
    private var showSubtitles by mutableStateOf(false)
    private data class SubtitleChoice(val name:String,val group:Tracks.Group?,val index:Int)
    private var subtitles by mutableStateOf<List<SubtitleChoice>>(listOf(SubtitleChoice("Off",null,-1)))
    private var subtitleCursor by mutableIntStateOf(0)
    private fun refreshSubtitles() {
        val list=mutableListOf(SubtitleChoice("Off",null,-1))
        player.currentTracks.groups.filter { it.type==C.TRACK_TYPE_TEXT }.forEach { group ->
            for(i in 0 until group.length) {
                if(!group.isTrackSupported(i))continue
                val format=group.getTrackFormat(i)
                val label=format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.takeIf { it.isNotBlank() && it!="und" }
                    ?: "Subtitle ${list.size}"
                list.add(SubtitleChoice(label,group,i))
            }
        }
        subtitles=list
        subtitleCursor=list.indexOfFirst { it.group!=null && it.group.isTrackSelected(it.index) }.coerceAtLeast(0)
    }
    private fun openSubtitles() { refreshSubtitles();showSubtitles=true;revealControls() }
    private fun chooseSubtitle() {
        val item=subtitles.getOrNull(subtitleCursor) ?: return
        val params=player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if(item.group==null)params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true)
        else params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false)
            .addOverride(TrackSelectionOverride(item.group.mediaTrackGroup,listOf(item.index)))
        player.trackSelectionParameters=params.build()
        showSubtitles=false;revealControls()
    }
    private var controlsVisible by mutableStateOf(true)
    private var playing by mutableStateOf(true)
    private var selectedControl by mutableIntStateOf(1)
    private fun activateControl() { when(selectedControl){0->seek(-10000);1->togglePlayback();2->seek(10000);3->{showSubtitles=true;revealControls()};4->revealControls() } }
    private var progress by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("playback",MODE_PRIVATE) }
    private val ticker=object:Runnable {
        override fun run() {
            if(::player.isInitialized) {
                progress=player.currentPosition.coerceAtLeast(0L)
                duration=player.duration.coerceAtLeast(0L)
                playing=player.isPlaying
                if(duration>0) prefs.edit().putLong(url,progress)
                    .putLong("duration:"+url,duration)
                    .putLong("updated:"+url,System.currentTimeMillis()).apply()
            }
            handler.postDelayed(this,1000)
        }
    }
    private val hideControls=Runnable { if(!showSubtitles)controlsVisible=false }
    private val remoteReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context?,intent:Intent?) {
            when(intent?.getStringExtra("key")) {
                "select"->if(showSubtitles)chooseSubtitle() else if(controlsVisible)activateControl() else togglePlayback()
                "toggle"->togglePlayback()
                "seekback"->seek(-10000)
                "seekforward"->seek(10000)
                "left"->{ if(selectedControl==4)seek(-10000) else if(controlsVisible)selectedControl=(selectedControl-1).coerceAtLeast(0) else seek(-10000);revealControls() }
                "right"->{ if(selectedControl==4)seek(10000) else if(controlsVisible)selectedControl=(selectedControl+1).coerceAtMost(3) else seek(10000);revealControls() }
                "up"->{if(showSubtitles)subtitleCursor=(subtitleCursor-1).coerceAtLeast(0) else selectedControl=4;revealControls()}
                "down"->{if(showSubtitles)subtitleCursor=(subtitleCursor+1).coerceAtMost(subtitles.lastIndex) else selectedControl=1;revealControls()}
                "show"->revealControls()
                "subtitles"->{ showSubtitles=true;revealControls() }
                "back"->finish()
            }
        }
    }
    private fun revealControls() {
        controlsVisible=true
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls,5500)
    }
    private fun togglePlayback() {
        if(player.isPlaying)player.pause() else player.play()
        playing=player.isPlaying
        revealControls()
    }
    private fun seek(delta:Long) {
        player.seekTo((player.currentPosition+delta).coerceIn(0L,player.duration.coerceAtLeast(0L)))
        revealControls()
    }
    private val subtitlePicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null){
            subtitleUri=uri
            subtitleType=if(uri.toString().substringBefore("?").endsWith(".srt",true))MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            val pos=player.currentPosition
            player.addListener(object:Player.Listener { override fun onTracksChanged(tracks:Tracks) { refreshSubtitles() } })
        player.setMediaItem(item());player.prepare();player.seekTo(pos);player.play()
        }
    }
    private fun item():MediaItem {
        val builder=MediaItem.Builder().setUri(url)
        subtitleUri?.let { uri ->
            builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(subtitleType).setLanguage("en").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        return builder.build()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        url=intent.getStringExtra("url") ?: run { finish();return }
        movieTitle=intent.getStringExtra("title")?.takeIf { it.isNotBlank() }
            ?: getSharedPreferences("playback",MODE_PRIVATE).getString("title:"+url,null)?.takeIf { it.isNotBlank() }
            ?: Uri.parse(url).host.orEmpty()
        player=ExoPlayer.Builder(this).build()
        player.setMediaItem(item());player.prepare()
        player.seekTo(prefs.getLong(url,0L));player.playWhenReady=true
        val filter=IntentFilter("za.co.tvbrowser.PLAYER_REMOTE")
        if(Build.VERSION.SDK_INT>=33)registerReceiver(remoteReceiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else { registerReceiver(remoteReceiver,filter) }
        handler.post(ticker)
        revealControls()
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory={ context -> PlayerView(context).apply {
                        player=this@PlayerActivity.player
                        useController=false
                        resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setOnClickListener { revealControls() }
                    } },modifier=Modifier.fillMaxSize())
                    if(controlsVisible) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Color(0xDD0A1825)).padding(horizontal=28.dp,vertical=18.dp),
                            verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(movieTitle.take(100),color=Color.White,fontSize=18.sp)
                            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                Text(if(selectedControl==4)"SEEK · Left / Right to move · OK to finish" else "PROGRESS · Press Up to seek",
                                    color=if(selectedControl==4)Color(0xFF79E8B6) else Color.White,fontSize=12.sp)
                                BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if(selectedControl==4)Color(0xFF356F67) else Color(0xFF344454))
                                    .pointerInput(duration) {
                                        detectTapGestures { offset -> seekToFraction(offset.x/size.width.toFloat());selectedControl=4 }
                                    }.clickable { selectedControl=4;revealControls() }.focusable(),
                                    contentAlignment=Alignment.CenterStart) {
                                    val fraction=if(duration>0)(progress.toFloat()/duration).coerceIn(0f,1f) else 0f
                                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color(0xFF79E8B6)))
                                    Box(Modifier.offset(x=maxWidth*fraction-7.dp).size(14.dp)
                                        .clip(RoundedCornerShape(50)).background(Color.White))
                                }
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,
                                verticalAlignment=Alignment.CenterVertically) {
                                Control(Icons.Rounded.Replay10,"Back 10 seconds",selectedControl==0) { seek(-10000) }
                                Control(if(playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,"Play or pause",selectedControl==1) { togglePlayback() }
                                Control(Icons.Rounded.Forward10,"Forward 10 seconds",selectedControl==2) { seek(10000) }
                                Control(Icons.Rounded.Subtitles,"Subtitles",selectedControl==3) { showSubtitles=true;revealControls() }
                                Text("${progress/60000} / ${duration/60000} min",color=Color.White,fontSize=15.sp)
                            }
                        }
                    }
                    if(showSubtitles) {
                        Column(Modifier.align(Alignment.Center).width(510.dp)
                            .clip(RoundedCornerShape(24.dp)).background(Color(0xFF1A3042)).padding(25.dp),
                            verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("Embedded subtitles",color=Color.White,fontSize=24.sp)
                            if(subtitles.size==1) Text("No embedded subtitle tracks found.",color=Color.LightGray,fontSize=15.sp)
                            subtitles.forEachIndexed { index,track ->
                                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .background(if(index==subtitleCursor)Color(0xFF397E6E) else Color(0xFF294457))
                                    .clickable { subtitleCursor=index;chooseSubtitle() }.focusable().padding(16.dp)) {
                                    Text((if(index==subtitleCursor)"✓  " else "")+track.name,color=Color.White,fontSize=18.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    @Composable private fun Control(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,selected:Boolean,click:()->Unit) {
        var focused by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal=10.dp).size(64.dp).clip(RoundedCornerShape(18.dp))
            .background(if(focused || selected)Color(0xFF397E6E) else Color(0xFF24394B))
            .onFocusChanged { focused=it.isFocused }.clickable(onClick=click).focusable(),
            contentAlignment=Alignment.Center) {
            Icon(icon,contentDescription=label,tint=Color.White,modifier=Modifier.size(30.dp))
        }
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(event.action==KeyEvent.ACTION_DOWN){
            if(showSubtitles) {
                when(event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_LEFT -> { subtitleCursor=(subtitleCursor-1).coerceAtLeast(0);return true }
                    KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT -> { subtitleCursor=(subtitleCursor+1).coerceAtMost(subtitles.lastIndex);return true }
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> { chooseSubtitle();return true }
                    KeyEvent.KEYCODE_BACK -> { showSubtitles=false;return true }
                }
            }
            when(event.keyCode){
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if(controlsVisible)activateControl() else togglePlayback()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if(selectedControl==4)seek(-10000) else if(controlsVisible)selectedControl=(selectedControl-1).coerceAtLeast(0) else seek(-10000)
                    revealControls();return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(selectedControl==4)seek(10000) else if(controlsVisible)selectedControl=(selectedControl+1).coerceAtMost(3) else seek(10000)
                    revealControls();return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {selectedControl=4;revealControls();return true}
                KeyEvent.KEYCODE_DPAD_DOWN -> {selectedControl=1;revealControls();return true}
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onPause() {
        if(::player.isInitialized) { prefs.edit().putLong(url,player.currentPosition).apply();player.pause() }
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(ticker);handler.removeCallbacks(hideControls)
        runCatching { unregisterReceiver(remoteReceiver) }
        if(::player.isInitialized)player.release()
        super.onDestroy()
    }
}
