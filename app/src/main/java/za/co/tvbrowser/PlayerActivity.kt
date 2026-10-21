package za.co.tvbrowser

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var key: String
    private val prefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        key = url
        player = ExoPlayer.Builder(this).build()
        val view = PlayerView(this).apply { this.player = this@PlayerActivity.player; useController = true }
        setContentView(view)
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        player.seekTo(prefs.getLong(key, 0L))
        player.playWhenReady = true
    }
    override fun onPause() {
        if (::player.isInitialized) {
            prefs.edit().putLong(key, player.currentPosition).apply()
            player.pause()
        }
        super.onPause()
    }
    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
