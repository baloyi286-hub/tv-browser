package za.co.tvbrowser

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var key: String
    private val prefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var subtitleUri: Uri? = null
    private var subtitleType = MimeTypes.TEXT_VTT
    private val subtitlePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            subtitleUri = uri
            subtitleType = if (uri.toString().substringBefore("?").endsWith(".srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            val pos = player.currentPosition
            player.setMediaItem(makeMediaItem())
            player.prepare()
            player.seekTo(pos)
            player.play()
        }
    }
    private fun makeMediaItem(): MediaItem {
        val builder = MediaItem.Builder().setUri(key)
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
        key = intent.getStringExtra("url") ?: run { finish(); return }
        player = ExoPlayer.Builder(this).build()
        val root = FrameLayout(this)
        val view = PlayerView(this).apply { this.player = this@PlayerActivity.player; useController = true }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        val subtitleButton = Button(this).apply {
            text = "CC / Subtitles"
            setOnClickListener {
                val choices = arrayOf("Use embedded subtitles (if available)", "Load subtitle file (.srt / .vtt)", "Disable subtitles")
                android.app.AlertDialog.Builder(this@PlayerActivity).setTitle("Subtitles").setItems(choices) { _, choice ->
                    when (choice) {
                        0 -> {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                            Toast.makeText(this@PlayerActivity, "Embedded subtitles enabled if present", Toast.LENGTH_SHORT).show()
                        }
                        1 -> subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
                        2 -> player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    }
                }.show()
            }
        }
        root.addView(subtitleButton, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        setContentView(root)
        player.setMediaItem(makeMediaItem())
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
