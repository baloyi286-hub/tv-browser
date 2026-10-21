package za.co.tvbrowser

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var url: String
    private var subtitleUri: Uri? = null
    private var subtitleType = MimeTypes.TEXT_VTT
    private var showSubtitles by mutableStateOf(false)
    private val prefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            subtitleUri = uri
            subtitleType = if (uri.toString().substringBefore("?").endsWith(".srt", true))
                MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            val position = player.currentPosition
            player.setMediaItem(item())
            player.prepare()
            player.seekTo(position)
            player.play()
        }
    }
    private fun item(): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        subtitleUri?.let { uri ->
            builder.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(subtitleType)
                    .setLanguage("en")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
        }
        return builder.build()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra("url") ?: run { finish(); return }
        player = ExoPlayer.Builder(this).build()
        player.setMediaItem(item())
        player.prepare()
        player.seekTo(prefs.getLong(url, 0L))
        player.playWhenReady = true
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF0D1929))) {
                    AndroidView(factory = { context -> PlayerView(context).apply {
                        player = this@PlayerActivity.player
                        useController = true
                    } }, modifier = Modifier.fillMaxSize())
                    Row(Modifier.align(Alignment.TopEnd).padding(22.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Color(0xFF24394B))
                        .clickable { showSubtitles = !showSubtitles }.padding(horizontal=20.dp,vertical=12.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Subtitles,contentDescription="Subtitles",tint=Color(0xFF79E8B6))
                        Text("Subtitles",color=Color.White,fontSize=16.sp)
                    }
                    if (showSubtitles) {
                        Column(Modifier.align(Alignment.Center).width(420.dp)
                            .clip(RoundedCornerShape(28.dp)).background(Color(0xFF1A3042)).padding(25.dp),
                            verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("Subtitles",color=Color.White,fontSize=25.sp)
                            listOf("Use embedded subtitles","Load SRT / VTT file","Disable subtitles","Close").forEachIndexed { index,label ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF294457)).clickable {
                                        when(index) {
                                            0 -> player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).build()
                                            1 -> subtitlePicker.launch(arrayOf("text/*","application/x-subrip","application/octet-stream"))
                                            2 -> player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
                                        }
                                        showSubtitles = false
                                    }.padding(16.dp)) {
                                    Text(label,color=Color.White,fontSize=18.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onPause() {
        if (::player.isInitialized) {
            prefs.edit().putLong(url,player.currentPosition).apply()
            player.pause()
        }
        super.onPause()
    }
    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
